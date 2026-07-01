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
    private static final HotSpotMethodBridge.NativeFunctionLink FROM_JOBJECT_LINK;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodHandle newGlobalRef = lookup.findStatic(
                    HotSpotGlobalRefs.class,
                    "newGlobalRef0Impl",
                    MethodType.methodType(long.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)
            );
            MethodHandle fromJObject = lookup.findStatic(
                    HotSpotGlobalRefs.class,
                    "fromJObject0Impl",
                    MethodType.methodType(MemorySegment.class, MemorySegment.class, MemorySegment.class, long.class)
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
            FROM_JOBJECT_LINK = register(
                    "fromJObject0",
                    MethodType.methodType(Object.class, long.class),
                    fromJObject,
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG
                    )
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
     * 把当前仍有效的 JNI {@code jobject} handle 转回 Java 对象。
     *
     * <p>{@code address} 可以是 global ref，也可以是当前 JNI native frame/JVMTI
     * callback 内仍有效的 local ref。这个方法不会让 stale local ref 变安全。
     */
    public static Object fromJObject(long address) {
        if (address == 0) {
            return null;
        }
        return fromJObject0(address);
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

    private static native Object fromJObject0(long jobject);

    private static long newGlobalRef0Impl(MemorySegment env, MemorySegment clazz, MemorySegment object) {
        return JNIEnv.of(env).newGlobalRef(object).address();
    }

    private static MemorySegment fromJObject0Impl(MemorySegment env, MemorySegment clazz, long jobject) {
        return MemorySegment.ofAddress(jobject);
    }
}
