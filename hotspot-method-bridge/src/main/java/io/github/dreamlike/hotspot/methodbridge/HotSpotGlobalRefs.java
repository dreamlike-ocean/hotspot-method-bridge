package io.github.dreamlike.hotspot.methodbridge;

import io.github.dreamlike.hotspot.vmstruct.jni.GlobalRef;
import io.github.dreamlike.hotspot.vmstruct.jni.JNIEnv;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * 不写本地库的 JNI global reference 辅助入口。
 *
 * <p>这里的私有 {@code native} 方法不会通过 {@code System.loadLibrary} 绑定，
 * 而是在类初始化时用 {@link HotSpotMethodBridge#registerNative(Method, MemorySegment)}
 * 直接绑定到 FFM upcall stub。这样 Java 对象参数会先经过 HotSpot native wrapper
 * 变成 JNI local handle，upcall 内再调用 {@code NewGlobalRef}，最后把 global handle
 * 地址作为 {@code long} 返回给 Java 层包装成 {@link GlobalRef}。
 */
public final class HotSpotGlobalRefs {
    private static final Arena UPCALL_ARENA = Arena.global();
    private static final HotSpotMethodBridge.NativeFunctionLink NEW_GLOBAL_REF_LINK;
    private static final HotSpotMethodBridge.NativeFunctionLink IS_SAME_OBJECT_LINK;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodHandle newGlobalRef = lookup.findStatic(
                    HotSpotGlobalRefs.class,
                    "newGlobalRef0Impl",
                    MethodType.methodType(long.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)
            );
            MethodHandle isSameObject = lookup.findStatic(
                    HotSpotGlobalRefs.class,
                    "isSameObject0Impl",
                    MethodType.methodType(byte.class, MemorySegment.class, MemorySegment.class, long.class, MemorySegment.class)
            );
            NEW_GLOBAL_REF_LINK = register(
                    "newGlobalRef0",
                    MethodType.methodType(long.class, Object.class),
                    newGlobalRef,
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS)
            );
            IS_SAME_OBJECT_LINK = register(
                    "isSameObject0",
                    MethodType.methodType(byte.class, long.class, Object.class),
                    isSameObject,
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_BYTE,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS)
            );
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private HotSpotGlobalRefs() {
    }

    /**
     * 为一个 Java 对象创建 JNI global reference。
     *
     * <p>返回的 {@link GlobalRef} 需要由调用方关闭；关闭时会调用
     * {@code DeleteGlobalRef} 释放 JNI global handle。
     */
    public static GlobalRef newGlobalRef(Object object) {
        Objects.requireNonNull(object, "object");
        long ref = newGlobalRef0(object);
        if (ref == 0) {
            throw new OutOfMemoryError("NewGlobalRef returned NULL");
        }
        return GlobalRef.wrap(ref);
    }

    /**
     * 用 JNI {@code IsSameObject} 判断 global reference 与 Java 对象是否指向同一对象。
     */
    public static boolean isSameObject(GlobalRef ref, Object object) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(object, "object");
        return isSameObject0(ref.address(), object) != 0;
    }

    private static HotSpotMethodBridge.NativeFunctionLink register(
            String nativeName,
            MethodType nativeType,
            MethodHandle implementation,
            FunctionDescriptor descriptor) throws ReflectiveOperationException {
        Method nativeMethod = HotSpotGlobalRefs.class.getDeclaredMethod(
                nativeName,
                nativeType.parameterArray()
        );
        MemorySegment stub = Linker.nativeLinker().upcallStub(implementation, descriptor, UPCALL_ARENA);
        return HotSpotMethodBridge.registerNative(nativeMethod, stub);
    }

    private static native long newGlobalRef0(Object object);

    private static native byte isSameObject0(long ref, Object object);

    private static long newGlobalRef0Impl(MemorySegment env, MemorySegment clazz, MemorySegment object) {
        return JNIEnv.of(env).newGlobalRef(object).address();
    }

    private static byte isSameObject0Impl(MemorySegment env, MemorySegment clazz, long ref, MemorySegment object) {
        return (byte) (JNIEnv.of(env).isSameObject(MemorySegment.ofAddress(ref), object) ? 1 : 0);
    }
}
