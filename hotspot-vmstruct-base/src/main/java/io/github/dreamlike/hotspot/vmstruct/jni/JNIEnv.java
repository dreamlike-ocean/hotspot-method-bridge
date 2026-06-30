package io.github.dreamlike.hotspot.vmstruct.jni;

import io.github.dreamlike.hotspot.vmstruct.HotSpotLibrary;
import io.github.dreamlike.hotspot.vmstruct.HotSpotMemory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Objects;

import static io.github.dreamlike.hotspot.vmstruct.jni.JNIEnvFunction.CALL_STATIC_OBJECT_METHOD_A;
import static io.github.dreamlike.hotspot.vmstruct.jni.JNIEnvFunction.CALL_STATIC_VOID_METHOD_A;
import static io.github.dreamlike.hotspot.vmstruct.jni.JNIEnvFunction.CALL_OBJECT_METHOD_A;
import static io.github.dreamlike.hotspot.vmstruct.jni.JNIEnvFunction.DELETE_GLOBAL_REF;
import static io.github.dreamlike.hotspot.vmstruct.jni.JNIEnvFunction.DELETE_LOCAL_REF;
import static io.github.dreamlike.hotspot.vmstruct.jni.JNIEnvFunction.EXCEPTION_CHECK;
import static io.github.dreamlike.hotspot.vmstruct.jni.JNIEnvFunction.EXCEPTION_CLEAR;
import static io.github.dreamlike.hotspot.vmstruct.jni.JNIEnvFunction.EXCEPTION_DESCRIBE;
import static io.github.dreamlike.hotspot.vmstruct.jni.JNIEnvFunction.FIND_CLASS;
import static io.github.dreamlike.hotspot.vmstruct.jni.JNIEnvFunction.FROM_REFLECTED_FIELD;
import static io.github.dreamlike.hotspot.vmstruct.jni.JNIEnvFunction.FROM_REFLECTED_METHOD;
import static io.github.dreamlike.hotspot.vmstruct.jni.JNIEnvFunction.GET_ARRAY_LENGTH;
import static io.github.dreamlike.hotspot.vmstruct.jni.JNIEnvFunction.GET_METHOD_ID;
import static io.github.dreamlike.hotspot.vmstruct.jni.JNIEnvFunction.GET_OBJECT_CLASS;
import static io.github.dreamlike.hotspot.vmstruct.jni.JNIEnvFunction.GET_STATIC_METHOD_ID;
import static io.github.dreamlike.hotspot.vmstruct.jni.JNIEnvFunction.GET_VERSION;
import static io.github.dreamlike.hotspot.vmstruct.jni.JNIEnvFunction.IS_VIRTUAL_THREAD;
import static io.github.dreamlike.hotspot.vmstruct.jni.JNIEnvFunction.NEW_BYTE_ARRAY;
import static io.github.dreamlike.hotspot.vmstruct.jni.JNIEnvFunction.NEW_GLOBAL_REF;
import static io.github.dreamlike.hotspot.vmstruct.jni.JNIEnvFunction.NEW_STRING_UTF;
import static io.github.dreamlike.hotspot.vmstruct.jni.JNIEnvFunction.SET_BYTE_ARRAY_REGION;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * {@code JNIEnv*} 的纯 FFM 包装。
 *
 * <p>这个类只负责拿到当前线程的 {@code JNIEnv*}、包装外部回调传入的
 * {@code JNIEnv*}，以及按 {@code JNINativeInterface_} 函数表调用 JNI 入口。
 *
 * <p>注意：JNI 返回的 {@code jobject}/{@code jclass}/{@code jstring} 默认是
 * local reference。它们的生命周期属于真实 JNI native frame 或 JVMTI callback
 * 当前回调范围。普通 Java 代码通过 FFM 逐个 downcall JNI 函数时，不能把 local
 * reference 当成跨多次 downcall 的稳定引用；需要长期持有时应在有效 JNI frame
 * 内转成 global reference。
 */
public final class JNIEnv {
    public static final int JNI_OK = 0;
    public static final int JNI_EDETACHED = -2;
    public static final int JNI_VERSION_1_8 = 0x00010008;

    private static final int JAVA_VM_GET_ENV_INDEX = 6;
    private static final MethodHandle JNI_GET_CREATED_JAVA_VMS = HotSpotMemory.LINKER.downcallHandle(
            HotSpotLibrary.libjvmLookup()
                    .find("JNI_GetCreatedJavaVMs")
                    .orElseThrow(() -> new IllegalStateException("symbol not found: JNI_GetCreatedJavaVMs")),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS)
    );
    private static final MethodHandle JAVA_VM_GET_ENV = HotSpotMemory.LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)
    );
    private static final MethodHandle GET_VERSION_MH = downcall(FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle FIND_CLASS_MH = downcall(FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle FROM_REFLECTED_METHOD_MH = downcall(FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle FROM_REFLECTED_FIELD_MH = downcall(FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle NEW_GLOBAL_REF_MH = downcall(FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle DELETE_GLOBAL_REF_MH = downcall(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle DELETE_LOCAL_REF_MH = downcall(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle GET_OBJECT_CLASS_MH = downcall(FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle GET_METHOD_ID_MH = downcall(FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle CALL_OBJECT_METHOD_A_MH = downcall(FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle GET_STATIC_METHOD_ID_MH = downcall(FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle CALL_STATIC_OBJECT_METHOD_A_MH = downcall(FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle CALL_STATIC_VOID_METHOD_A_MH = downcall(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle NEW_STRING_UTF_MH = downcall(FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle GET_ARRAY_LENGTH_MH = downcall(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle NEW_BYTE_ARRAY_MH = downcall(FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle SET_BYTE_ARRAY_REGION_MH = downcall(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle EXCEPTION_CHECK_MH = downcall(FunctionDescriptor.of(JAVA_BYTE, ADDRESS));
    private static final MethodHandle EXCEPTION_DESCRIBE_MH = downcall(FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle EXCEPTION_CLEAR_MH = downcall(FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle IS_VIRTUAL_THREAD_MH = downcall(FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS));

    private static final MemorySegment JAVA_VM = findJavaVM();
    private static final ThreadLocal<JNIEnv> CURRENT = ThreadLocal.withInitial(() -> new JNIEnv(currentEnvPointer()));

    private final MemorySegment pointer;

    private JNIEnv(MemorySegment pointer) {
        if (pointer == MemorySegment.NULL) {
            throw new IllegalArgumentException("JNIEnv pointer is NULL");
        }
        this.pointer = pointer.reinterpret(Long.MAX_VALUE);
    }

    public static JNIEnv current() {
        return CURRENT.get();
    }

    public static JNIEnv of(MemorySegment env) {
        return new JNIEnv(env);
    }

    public static JNIEnv ofAddress(long env) {
        return of(MemorySegment.ofAddress(env));
    }

    public static MemorySegment javaVM() {
        return JAVA_VM;
    }

    public MemorySegment pointer() {
        return pointer;
    }

    public int getVersion() {
        try {
            return (int) GET_VERSION_MH.invokeExact(function(GET_VERSION), pointer);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public MemorySegment findClassLocal(String internalName) {
        Objects.requireNonNull(internalName, "internalName");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment result = (MemorySegment) FIND_CLASS_MH.invokeExact(
                    function(FIND_CLASS),
                    pointer,
                    arena.allocateFrom(internalName)
            );
            if (isNull(result)) {
                checkException("FindClass(" + internalName + ")");
            }
            return result;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public GlobalRef findClass(String internalName) {
        MemorySegment local = findClassLocal(internalName);
        try {
            return globalRef(local);
        } finally {
            deleteLocalRef(local);
        }
    }

    public GlobalRef findClass(Class<?> type) {
        if (type.isPrimitive()) {
            throw new IllegalArgumentException("primitive class has no JNI class handle: " + type);
        }
        return findClass(type.isArray() ? JNISignature.of(type) : JNISignature.internalName(type));
    }

    public MemorySegment fromReflectedMethod(MemorySegment reflectedMethod) {
        try {
            MemorySegment result = (MemorySegment) FROM_REFLECTED_METHOD_MH.invokeExact(
                    function(FROM_REFLECTED_METHOD),
                    pointer,
                    reflectedMethod
            );
            checkException("FromReflectedMethod");
            return result;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public MemorySegment fromReflectedField(MemorySegment reflectedField) {
        try {
            MemorySegment result = (MemorySegment) FROM_REFLECTED_FIELD_MH.invokeExact(
                    function(FROM_REFLECTED_FIELD),
                    pointer,
                    reflectedField
            );
            checkException("FromReflectedField");
            return result;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public MemorySegment newGlobalRef(MemorySegment object) {
        try {
            MemorySegment result = (MemorySegment) NEW_GLOBAL_REF_MH.invokeExact(
                    function(NEW_GLOBAL_REF),
                    pointer,
                    object
            );
            checkException("NewGlobalRef");
            return result;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public GlobalRef globalRef(MemorySegment object) {
        return new GlobalRef(this, newGlobalRef(object));
    }

    public GlobalRef wrapGlobalRef(MemorySegment globalRef) {
        return new GlobalRef(this, globalRef);
    }

    public void deleteGlobalRef(MemorySegment globalRef) {
        try {
            DELETE_GLOBAL_REF_MH.invokeExact(function(DELETE_GLOBAL_REF), pointer, globalRef);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void deleteLocalRef(MemorySegment localRef) {
        if (localRef == MemorySegment.NULL) {
            return;
        }
        try {
            DELETE_LOCAL_REF_MH.invokeExact(function(DELETE_LOCAL_REF), pointer, localRef);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public MemorySegment getObjectClass(MemorySegment object) {
        try {
            MemorySegment result = (MemorySegment) GET_OBJECT_CLASS_MH.invokeExact(
                    function(GET_OBJECT_CLASS),
                    pointer,
                    object
            );
            if (isNull(result)) {
                checkException("GetObjectClass");
            }
            return result;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public MemorySegment getMethodId(MemorySegment clazz, String name, String signature) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(signature, "signature");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment result = (MemorySegment) GET_METHOD_ID_MH.invokeExact(
                    function(GET_METHOD_ID),
                    pointer,
                    clazz,
                    arena.allocateFrom(name),
                    arena.allocateFrom(signature)
            );
            checkException("GetMethodID(" + name + signature + ")");
            return result;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public MemorySegment getStaticMethodId(MemorySegment clazz, String name, String signature) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(signature, "signature");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment result = (MemorySegment) GET_STATIC_METHOD_ID_MH.invokeExact(
                    function(GET_STATIC_METHOD_ID),
                    pointer,
                    clazz,
                    arena.allocateFrom(name),
                    arena.allocateFrom(signature)
            );
            checkException("GetStaticMethodID(" + name + signature + ")");
            return result;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public MemorySegment callStaticObjectMethodA(MemorySegment clazz, MemorySegment methodId, MemorySegment args) {
        try {
            MemorySegment result = (MemorySegment) CALL_STATIC_OBJECT_METHOD_A_MH.invokeExact(
                    function(CALL_STATIC_OBJECT_METHOD_A),
                    pointer,
                    clazz,
                    methodId,
                    args
            );
            if (isNull(result)) {
                checkException("CallStaticObjectMethodA");
            }
            return result;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public MemorySegment callObjectMethodA(MemorySegment object, MemorySegment methodId, MemorySegment args) {
        try {
            MemorySegment result = (MemorySegment) CALL_OBJECT_METHOD_A_MH.invokeExact(
                    function(CALL_OBJECT_METHOD_A),
                    pointer,
                    object,
                    methodId,
                    args
            );
            if (isNull(result)) {
                checkException("CallObjectMethodA");
            }
            return result;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void callStaticVoidMethodA(MemorySegment clazz, MemorySegment methodId, MemorySegment args) {
        try {
            CALL_STATIC_VOID_METHOD_A_MH.invokeExact(
                    function(CALL_STATIC_VOID_METHOD_A),
                    pointer,
                    clazz,
                    methodId,
                    args
            );
            checkException("CallStaticVoidMethodA");
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public MemorySegment newStringUtf(String value) {
        Objects.requireNonNull(value, "value");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment result = (MemorySegment) NEW_STRING_UTF_MH.invokeExact(
                    function(NEW_STRING_UTF),
                    pointer,
                    arena.allocateFrom(value)
            );
            if (isNull(result)) {
                checkException("NewStringUTF");
            }
            return result;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public int getArrayLength(MemorySegment array) {
        try {
            int result = (int) GET_ARRAY_LENGTH_MH.invokeExact(function(GET_ARRAY_LENGTH), pointer, array);
            checkException("GetArrayLength");
            return result;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public MemorySegment newByteArray(int length) {
        try {
            MemorySegment result = (MemorySegment) NEW_BYTE_ARRAY_MH.invokeExact(
                    function(NEW_BYTE_ARRAY),
                    pointer,
                    length
            );
            if (isNull(result)) {
                checkException("NewByteArray");
            }
            return result;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public MemorySegment newByteArray(byte[] bytes) {
        MemorySegment array = newByteArray(bytes.length);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment source = arena.allocate(JAVA_BYTE, bytes.length);
            source.copyFrom(MemorySegment.ofArray(bytes));
            setByteArrayRegion(array, 0, bytes.length, source);
            return array;
        }
    }

    public void setByteArrayRegion(MemorySegment array, int start, int length, MemorySegment source) {
        try {
            SET_BYTE_ARRAY_REGION_MH.invokeExact(
                    function(SET_BYTE_ARRAY_REGION),
                    pointer,
                    array,
                    start,
                    length,
                    source
            );
            checkException("SetByteArrayRegion");
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public boolean exceptionCheck() {
        try {
            return ((byte) EXCEPTION_CHECK_MH.invokeExact(function(EXCEPTION_CHECK), pointer)) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void exceptionDescribe() {
        try {
            EXCEPTION_DESCRIBE_MH.invokeExact(function(EXCEPTION_DESCRIBE), pointer);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void exceptionClear() {
        try {
            EXCEPTION_CLEAR_MH.invokeExact(function(EXCEPTION_CLEAR), pointer);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public boolean isVirtualThread(MemorySegment threadObject) {
        try {
            byte result = (byte) IS_VIRTUAL_THREAD_MH.invokeExact(
                    function(IS_VIRTUAL_THREAD),
                    pointer,
                    threadObject
            );
            checkException("IsVirtualThread");
            return result != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public MemorySegment function(JNIEnvFunction function) {
        long tableSize = (long) (function.index() + 1) * HotSpotMemory.ADDRESS_SIZE;
        MemorySegment functions = pointer.get(ADDRESS, 0).reinterpret(tableSize);
        return functions.get(ADDRESS, (long) function.index() * HotSpotMemory.ADDRESS_SIZE);
    }

    private static boolean isNull(MemorySegment segment) {
        return segment == MemorySegment.NULL || segment.address() == 0;
    }

    private static MethodHandle downcall(FunctionDescriptor descriptor) {
        return HotSpotMemory.LINKER.downcallHandle(descriptor);
    }

    private static MemorySegment findJavaVM() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment vmBuffer = arena.allocate(ADDRESS);
            MemorySegment vmCount = arena.allocate(JAVA_INT);
            int result = (int) JNI_GET_CREATED_JAVA_VMS.invokeExact(vmBuffer, 1, vmCount);
            if (result != JNI_OK) {
                throw new IllegalStateException("JNI_GetCreatedJavaVMs failed: " + result);
            }
            int count = vmCount.get(JAVA_INT, 0);
            if (count < 1) {
                throw new IllegalStateException("JNI_GetCreatedJavaVMs returned no VM");
            }
            MemorySegment vm = vmBuffer.get(ADDRESS, 0);
            if (vm == MemorySegment.NULL) {
                throw new IllegalStateException("JNI_GetCreatedJavaVMs returned NULL VM");
            }
            return MemorySegment.ofAddress(vm.address()).reinterpret(Long.MAX_VALUE);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static MemorySegment currentEnvPointer() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment envBuffer = arena.allocate(ADDRESS);
            MemorySegment functions = JAVA_VM.get(ADDRESS, 0)
                    .reinterpret((long) (JAVA_VM_GET_ENV_INDEX + 1) * HotSpotMemory.ADDRESS_SIZE);
            MemorySegment getEnv = functions.get(
                    ADDRESS,
                    (long) JAVA_VM_GET_ENV_INDEX * HotSpotMemory.ADDRESS_SIZE
            );
            int result = (int) JAVA_VM_GET_ENV.invokeExact(getEnv, JAVA_VM, envBuffer, JNI_VERSION_1_8);
            if (result == JNI_EDETACHED) {
                throw new IllegalStateException("current thread is not attached to the VM");
            }
            if (result != JNI_OK) {
                throw new IllegalStateException("JavaVM::GetEnv failed: " + result);
            }
            MemorySegment env = envBuffer.get(ADDRESS, 0);
            if (env == MemorySegment.NULL) {
                throw new IllegalStateException("JavaVM::GetEnv returned NULL JNIEnv");
            }
            return MemorySegment.ofAddress(env.address()).reinterpret(Long.MAX_VALUE);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private void checkException(String operation) {
        if (exceptionCheck()) {
            exceptionDescribe();
            exceptionClear();
            throw new IllegalStateException("JNI exception pending after " + operation);
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException e) {
            return e;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(t);
    }
}
