package io.github.dreamlike.hotspot.methodbridge;

import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.util.concurrent.locks.LockSupport;

public final class HotSpotMethodBridge {
    static final Linker LINKER = Linker.nativeLinker();
    private static final long ADDRESS_SIZE = ValueLayout.ADDRESS.byteSize();
    // 来源：src/hotspot/share/oops/methodFlags.hpp 的 MethodFlags。
    // 1<<8 is_not_c2_compilable, 1<<9 is_not_c1_compilable,
    // 1<<10 is_not_c2_osr_compilable, 1<<12 dont_inline。
    private static final int METHOD_FLAGS_TARGET_BITS = (1 << 8) | (1 << 9) | (1 << 10) | (1 << 12);
    private static final VarHandle INT_HANDLE = ValueLayout.JAVA_INT.varHandle();

    private static final MethodLayout JVM_METHOD_LAYOUT = MethodLayout.load();
    static final CodeBlobLayout CODE_BLOB_LAYOUT = CodeBlobLayout.load();
    private static final NarrowOopEncoding NARROW_OOP_ENCODING = NarrowOopEncoding.load();
    static final NativeSymbols NATIVE_SYMBOLS = NativeSymbolsHolder.current();

    private HotSpotMethodBridge() {
    }

    //只是用来替换两个方法的把carrier的绑定到target上
    public static Link linkToCarrier(Method target, Method carrier) {
        long targetMethod = methodAddress(target);
        long carrierMethod = methodAddress(carrier);

        long carrierCode = getAddress(carrierMethod + JVM_METHOD_LAYOUT.methodCodeOffset);
        long carrierCompiledEntry = getAddress(carrierMethod + JVM_METHOD_LAYOUT.methodCompiledEntryOffset);
        long carrierInterpretedEntry = getAddress(carrierMethod + JVM_METHOD_LAYOUT.methodInterpretedEntryOffset);
        if (carrierCode == 0 || carrierCompiledEntry == 0 || carrierInterpretedEntry == 0) {
            throw new IllegalStateException("carrier is not compiled/linkable");
        }

        suppressTargetCompilation(targetMethod);

        //这两个屏障是在仿 HotSpot 自己 Method::set_code 的发布顺序。
        //mh->_code = code;
        //OrderAccess::storestore();
        //mh->_from_compiled_entry = code->verified_entry_point();
        //OrderAccess::storestore();
        //mh->_from_interpreted_entry = mh->get_i2c_entry();
        //如果没有屏障，CPU/JIT 理论上可以重排这些普通 native memory store。最坏情况是解释器先看到新的 _from_interpreted_entry，但 _from_compiled_entry 或 _code 还没对其他线程可见，调用链会短暂处在不一致状态。
        putAddress(targetMethod + JVM_METHOD_LAYOUT.methodCodeOffset, carrierCode);
        VarHandle.storeStoreFence();
        putAddress(targetMethod + JVM_METHOD_LAYOUT.methodCompiledEntryOffset, carrierCompiledEntry);
        VarHandle.storeStoreFence();
        putAddress(targetMethod + JVM_METHOD_LAYOUT.methodInterpretedEntryOffset, carrierInterpretedEntry);

        return new Link(targetMethod, carrierMethod, carrierCode, carrierCompiledEntry, carrierInterpretedEntry);
    }

    public static RawCodeLink linkToMachineCode(Method target, byte[] code) {
        if (code.length == 0) {
            throw new IllegalArgumentException("code is empty");
        }
        long targetMethod = methodAddress(target);
        if (getAddress(targetMethod + JVM_METHOD_LAYOUT.methodCodeOffset) != 0) {
            throw new IllegalStateException("target already has nmethod");
        }

        NativeCode rawCode = MethodRuntimeBridge.installCodeBlob(code);
        long i2cEntry = MethodRuntimeBridge.i2cEntry(targetMethod);
        suppressTargetCompilation(targetMethod);

        putAddress(targetMethod + JVM_METHOD_LAYOUT.methodCodeOffset, 0);
        VarHandle.storeStoreFence();
        putAddress(targetMethod + JVM_METHOD_LAYOUT.methodCompiledEntryOffset, rawCode.entry());
        VarHandle.storeStoreFence();
        putAddress(targetMethod + JVM_METHOD_LAYOUT.methodInterpretedEntryOffset, i2cEntry);

        return new RawCodeLink(targetMethod, rawCode.blob(), rawCode.entry(), rawCode.size(), i2cEntry);
    }

    private static void suppressTargetCompilation(long targetMethod) {
        long flagsAddress = targetMethod + JVM_METHOD_LAYOUT.methodFlagsOffset;
        // 禁止编译和inline防止替换之后被jvm修改
        INT_HANDLE.getAndBitwiseOr(memory(flagsAddress, Integer.BYTES), 0L, METHOD_FLAGS_TARGET_BITS);
    }

    public static boolean isCurrent(Link link) {
        return getAddress(link.targetMethod + JVM_METHOD_LAYOUT.methodCodeOffset) == link.code
                && getAddress(link.targetMethod + JVM_METHOD_LAYOUT.methodCompiledEntryOffset) == link.compiledEntry
                && getAddress(link.targetMethod + JVM_METHOD_LAYOUT.methodInterpretedEntryOffset) == link.interpretedEntry
                && getAddress(link.carrierMethod + JVM_METHOD_LAYOUT.methodCodeOffset) == link.code
                && getAddress(link.carrierMethod + JVM_METHOD_LAYOUT.methodCompiledEntryOffset) == link.compiledEntry
                && getAddress(link.carrierMethod + JVM_METHOD_LAYOUT.methodInterpretedEntryOffset) == link.interpretedEntry;
    }

    public static boolean isCurrent(RawCodeLink link) {
        return getAddress(link.targetMethod + JVM_METHOD_LAYOUT.methodCodeOffset) == 0
                && getAddress(link.targetMethod + JVM_METHOD_LAYOUT.methodCompiledEntryOffset) == link.entry
                && getAddress(link.targetMethod + JVM_METHOD_LAYOUT.methodInterpretedEntryOffset) == link.interpretedEntry;
    }

    public static boolean compileNow(Method method, int level) {
        return MethodRuntimeBridge.compile(methodAddress(method), level);
    }

    // jit可能是异步的 但是你可以参考io.github.dreamlike.hotspot.methodbridge.HotSpotMethodBridgeTest.installCompilerDirectives做个同步的
    public static MethodState waitForCode(Method method, long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        MethodState state;
        do {
            state = state(method);
            if (state.code != 0) {
                return state;
            }
            LockSupport.parkNanos(1_000_000L);
        } while (System.nanoTime() < deadline);
        return state;
    }

    public static MethodState state(Method method) {
        long methodAddress = methodAddress(method);
        return new MethodState(
                methodAddress,
                getAddress(methodAddress + JVM_METHOD_LAYOUT.methodInterpreterEntryOffset),
                getAddress(methodAddress + JVM_METHOD_LAYOUT.methodCodeOffset),
                getAddress(methodAddress + JVM_METHOD_LAYOUT.methodCompiledEntryOffset),
                getAddress(methodAddress + JVM_METHOD_LAYOUT.methodInterpretedEntryOffset));
    }

    private static long methodAddress(Method method) {
        return MethodAddressResolver.methodAddress(method);
    }

    static long decodeObjectReference(long value, int referenceSize) {
        return switch (referenceSize) {
            case Integer.BYTES -> NARROW_OOP_ENCODING.decode(value);
            case Long.BYTES -> value;
            default -> throw new IllegalArgumentException("unsupported reference size: " + referenceSize);
        };
    }

    static MemorySegment memory(long address, long byteSize) {
        return MemorySegment.ofAddress(address).reinterpret(byteSize);
    }

    static long getAddress(long address) {
        return memory(address, ADDRESS_SIZE).get(ValueLayout.ADDRESS, 0).address();
    }

    private static void putAddress(long address, long value) {
        memory(address, ADDRESS_SIZE).set(ValueLayout.ADDRESS, 0, value == 0 ? MemorySegment.NULL : MemorySegment.ofAddress(value));
    }

    static int getInt(long address) {
        return memory(address, Integer.BYTES).get(ValueLayout.JAVA_INT, 0);
    }

    static long getLong(long address) {
        return memory(address, Long.BYTES).get(ValueLayout.JAVA_LONG, 0);
    }

    static byte getByte(long address) {
        return memory(address, Byte.BYTES).get(ValueLayout.JAVA_BYTE, 0);
    }

    public record MethodState(long method, long interpreterEntry, long code, long compiledEntry, long interpretedEntry) {
    }

    public record Link(long targetMethod, long carrierMethod, long code, long compiledEntry, long interpretedEntry) {
    }

    public record RawCodeLink(long targetMethod, long blob, long entry, int size, long interpretedEntry) {
    }

    private record MethodLayout(
            long methodFlagsOffset,
            long methodInterpreterEntryOffset,
            long methodCodeOffset,
            long methodCompiledEntryOffset,
            long methodInterpretedEntryOffset) {

        private static MethodLayout load() {
            VmStructs vm = new VmStructs();
            return new MethodLayout(
                    // 这个flag确实是没在vmstruct中导出 所以只能根据源码倒推了
                    vm.offset("Method", "_intrinsic_id") - Integer.BYTES,
                    vm.offset("Method", "_i2i_entry"),
                    vm.offset("Method", "_code"),
                    vm.offset("Method", "_from_compiled_entry"),
                    vm.offset("Method", "_from_interpreted_entry"));
        }
    }

    record CodeBlobLayout(long codeOffset) {
        private static CodeBlobLayout load() {
            VmStructs vm = new VmStructs();
            return new CodeBlobLayout(vm.offset("CodeBlob", "_code_offset"));
        }
    }

    private record NarrowOopEncoding(long base, int shift) {
        private static NarrowOopEncoding load() {
            VmStructs vm = new VmStructs();
            return new NarrowOopEncoding(
                    getAddress(vm.staticAddress("CompressedOops", "_base")),
                    getInt(vm.staticAddress("CompressedOops", "_shift")));
        }

        private long decode(long value) {
            return value == 0 ? 0 : base + (value << shift);
        }
    }

}
