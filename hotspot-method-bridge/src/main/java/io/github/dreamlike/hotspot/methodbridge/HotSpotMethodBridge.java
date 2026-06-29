package io.github.dreamlike.hotspot.methodbridge;

import io.github.dreamlike.hotspot.vmstruct.MethodLayout;
import io.github.dreamlike.hotspot.vmstruct.NarrowOopEncoding;
import io.github.dreamlike.hotspot.vmstruct.NativeCode;
import io.github.dreamlike.hotspot.vmstruct.NativeSymbols;
import io.github.dreamlike.hotspot.vmstruct.NativeSymbolsHolder;

import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;

import static io.github.dreamlike.hotspot.vmstruct.HotSpotMemory.getAddress;
import static io.github.dreamlike.hotspot.vmstruct.HotSpotMemory.memory;
import static io.github.dreamlike.hotspot.vmstruct.HotSpotMemory.putAddress;

public final class HotSpotMethodBridge {
    // 来源：src/hotspot/share/oops/methodFlags.hpp 的 MethodFlags。
    // 1<<8 is_not_c2_compilable, 1<<9 is_not_c1_compilable,
    // 1<<10 is_not_c2_osr_compilable, 1<<12 dont_inline。
    private static final int METHOD_FLAGS_TARGET_BITS = (1 << 8) | (1 << 9) | (1 << 10) | (1 << 12);
    private static final VarHandle INT_HANDLE = ValueLayout.JAVA_INT.varHandle();

    private static final MethodLayout JVM_METHOD_LAYOUT = MethodLayout.load();
    private static final NarrowOopEncoding NARROW_OOP_ENCODING = NarrowOopEncoding.load();
    static final NativeSymbols NATIVE_SYMBOLS = NativeSymbolsHolder.current();

    private HotSpotMethodBridge() {
    }

    //只是用来替换两个方法的把carrier的绑定到target上
    public static Link linkToCarrier(Method target, Method carrier) {
        long targetMethod = methodAddress(target);
        long carrierMethod = methodAddress(carrier);

        long carrierCode = getAddress(carrierMethod + JVM_METHOD_LAYOUT.methodCodeOffset());
        long carrierCompiledEntry = getAddress(carrierMethod + JVM_METHOD_LAYOUT.methodCompiledEntryOffset());
        long carrierInterpretedEntry = getAddress(carrierMethod + JVM_METHOD_LAYOUT.methodInterpretedEntryOffset());
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
        putAddress(targetMethod + JVM_METHOD_LAYOUT.methodCodeOffset(), carrierCode);
        VarHandle.storeStoreFence();
        putAddress(targetMethod + JVM_METHOD_LAYOUT.methodCompiledEntryOffset(), carrierCompiledEntry);
        VarHandle.storeStoreFence();
        putAddress(targetMethod + JVM_METHOD_LAYOUT.methodInterpretedEntryOffset(), carrierInterpretedEntry);

        return new Link(targetMethod, carrierMethod, carrierCode, carrierCompiledEntry, carrierInterpretedEntry);
    }

    public static RawCodeLink linkToMachineCode(Method target, byte[] code) {
        if (code.length == 0) {
            throw new IllegalArgumentException("code is empty");
        }
        long targetMethod = methodAddress(target);
        if (getAddress(targetMethod + JVM_METHOD_LAYOUT.methodCodeOffset()) != 0) {
            throw new IllegalStateException("target already has nmethod");
        }

        NativeCode rawCode = MethodRuntimeBridge.installCodeBlob(rawCodeBlobName(target, code), code);
        long i2cEntry = MethodRuntimeBridge.i2cEntry(targetMethod);
        suppressTargetCompilation(targetMethod);

        putAddress(targetMethod + JVM_METHOD_LAYOUT.methodCodeOffset(), 0);
        VarHandle.storeStoreFence();
        putAddress(targetMethod + JVM_METHOD_LAYOUT.methodCompiledEntryOffset(), rawCode.entry());
        VarHandle.storeStoreFence();
        putAddress(targetMethod + JVM_METHOD_LAYOUT.methodInterpretedEntryOffset(), i2cEntry);

        return new RawCodeLink(targetMethod, rawCode.blob(), rawCode.entry(), rawCode.size(), i2cEntry);
    }

    private static String rawCodeBlobName(Method target, byte[] code) {
        return "hotspot-method-bridge " + target.toGenericString()
                + " " + code.length + "b/" + Integer.toUnsignedString(Arrays.hashCode(code), 16);
    }

    private static void suppressTargetCompilation(long targetMethod) {
        long flagsAddress = targetMethod + JVM_METHOD_LAYOUT.methodFlagsOffset();
        // 禁止编译和inline防止替换之后被jvm修改
        INT_HANDLE.getAndBitwiseOr(memory(flagsAddress, Integer.BYTES), 0L, METHOD_FLAGS_TARGET_BITS);
    }

    public static boolean isCurrent(Link link) {
        return getAddress(link.targetMethod + JVM_METHOD_LAYOUT.methodCodeOffset()) == link.code
                && getAddress(link.targetMethod + JVM_METHOD_LAYOUT.methodCompiledEntryOffset()) == link.compiledEntry
                && getAddress(link.targetMethod + JVM_METHOD_LAYOUT.methodInterpretedEntryOffset()) == link.interpretedEntry
                && getAddress(link.carrierMethod + JVM_METHOD_LAYOUT.methodCodeOffset()) == link.code
                && getAddress(link.carrierMethod + JVM_METHOD_LAYOUT.methodCompiledEntryOffset()) == link.compiledEntry
                && getAddress(link.carrierMethod + JVM_METHOD_LAYOUT.methodInterpretedEntryOffset()) == link.interpretedEntry;
    }

    public static boolean isCurrent(RawCodeLink link) {
        return getAddress(link.targetMethod + JVM_METHOD_LAYOUT.methodCodeOffset()) == 0
                && getAddress(link.targetMethod + JVM_METHOD_LAYOUT.methodCompiledEntryOffset()) == link.entry
                && getAddress(link.targetMethod + JVM_METHOD_LAYOUT.methodInterpretedEntryOffset()) == link.interpretedEntry;
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
                getAddress(methodAddress + JVM_METHOD_LAYOUT.methodInterpreterEntryOffset()),
                getAddress(methodAddress + JVM_METHOD_LAYOUT.methodCodeOffset()),
                getAddress(methodAddress + JVM_METHOD_LAYOUT.methodCompiledEntryOffset()),
                getAddress(methodAddress + JVM_METHOD_LAYOUT.methodInterpretedEntryOffset()));
    }

    private static long methodAddress(Method method) {
        return MethodAddressResolver.methodAddress(method);
    }

    public static long decodeObjectReference(long value, int referenceSize) {
        return NARROW_OOP_ENCODING.decodeReference(value, referenceSize);
    }

    public record MethodState(long method, long interpreterEntry, long code, long compiledEntry, long interpretedEntry) {
    }

    public record Link(long targetMethod, long carrierMethod, long code, long compiledEntry, long interpretedEntry) {
    }

    public record RawCodeLink(long targetMethod, long blob, long entry, int size, long interpretedEntry) {
    }

}
