package io.github.dreamlike.hotspot.methodbridge;

import io.github.dreamlike.hotspot.vmstruct.HotSpotMemory;
import io.github.dreamlike.hotspot.vmstruct.NativeCode;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

final class MethodRuntimeBridge {
    private static final int INVOCATION_ENTRY_BCI = -1;

    //todo 尽可能不要依赖于cpp符号 后面看看怎么搞
    private static final MethodHandle JAVA_THREAD_CURRENT = HotSpotMemory.LINKER.downcallHandle(
            MemorySegment.ofAddress(HotSpotMethodBridge.NATIVE_SYMBOLS.threadCurrent()),
            FunctionDescriptor.of(ValueLayout.ADDRESS));
    private static final MethodHandle WHITEBOX_COMPILE_METHOD = HotSpotMemory.LINKER.downcallHandle(
            MemorySegment.ofAddress(HotSpotMethodBridge.NATIVE_SYMBOLS.whiteBoxCompileMethod()),
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle METHOD_GET_I2C_ENTRY = HotSpotMemory.LINKER.downcallHandle(
            MemorySegment.ofAddress(HotSpotMethodBridge.NATIVE_SYMBOLS.methodGetI2cEntry()),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    private MethodRuntimeBridge() {
    }

    static boolean compile(long method, int level) {
        try {
            MemorySegment thread = (MemorySegment) JAVA_THREAD_CURRENT.invokeExact();
            return (boolean) WHITEBOX_COMPILE_METHOD.invokeExact(
                    MemorySegment.ofAddress(method),
                    level,
                    INVOCATION_ENTRY_BCI,
                    thread);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    static long i2cEntry(long method) {
        try {
            MemorySegment entry = (MemorySegment) METHOD_GET_I2C_ENTRY.invokeExact(MemorySegment.ofAddress(method));
            return entry.address();
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    static NativeCode installCodeBlob(String name, byte[] code) {
        return HotSpotMethodBridge.NATIVE_SYMBOLS.installCodeBlob(name, code);
    }
}
