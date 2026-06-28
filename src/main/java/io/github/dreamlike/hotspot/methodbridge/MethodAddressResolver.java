package io.github.dreamlike.hotspot.methodbridge;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

final class MethodAddressResolver {
    private static final long ADDRESS_SIZE = ValueLayout.ADDRESS.byteSize();
    private static final String BRIDGE_CLASS_NAME = JniMethodAddressBridge.class.getName();
    private static final int JNI_VERSION_1_8 = 0x0001_0008;
    private static final int FIND_CLASS = 6;
    private static final int FROM_REFLECTED_METHOD = 7;
    private static final int NEW_GLOBAL_REF = 21;
    private static final int EXCEPTION_DESCRIBE = 16;
    private static final int EXCEPTION_CLEAR = 17;
    private static final int GET_METHOD_ID = 33;
    private static final int CALL_OBJECT_METHOD_A = 36;
    private static final int GET_STATIC_METHOD_ID = 113;
    private static final int CALL_STATIC_OBJECT_METHOD_A = 116;
    private static final int NEW_STRING_UTF = 167;
    private static final int REGISTER_NATIVES = 215;
    private static final int EXCEPTION_CHECK = 228;
    private static final Arena ARENA = Arena.global();
    private static final MethodHandle JNI_GET_CREATED_JAVA_VMS = HotSpotMethodBridge.LINKER.downcallHandle(
            SymbolLookup.libraryLookup(VmStructs.libjvm(), ARENA)
                    .find("JNI_GetCreatedJavaVMs")
                    .orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle FIND_CLASS_HANDLE;
    private static final MethodHandle FROM_REFLECTED_METHOD_HANDLE;
    private static final MethodHandle NEW_GLOBAL_REF_HANDLE;
    private static final MethodHandle EXCEPTION_DESCRIBE_HANDLE;
    private static final MethodHandle EXCEPTION_CLEAR_HANDLE;
    private static final MethodHandle GET_METHOD_ID_HANDLE;
    private static final MethodHandle CALL_OBJECT_METHOD_A_HANDLE;
    private static final MethodHandle GET_STATIC_METHOD_ID_HANDLE;
    private static final MethodHandle CALL_STATIC_OBJECT_METHOD_A_HANDLE;
    private static final MethodHandle NEW_STRING_UTF_HANDLE;
    private static final MethodHandle REGISTER_NATIVES_HANDLE;
    private static final MethodHandle EXCEPTION_CHECK_HANDLE;
    private static final MethodHandle CHECKED_RESOLVE_JMETHOD_ID = HotSpotMethodBridge.LINKER.downcallHandle(
            MemorySegment.ofAddress(HotSpotMethodBridge.NATIVE_SYMBOLS.checkedResolveJmethodId()),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    static {
        MemorySegment env = currentJniEnv();
        FIND_CLASS_HANDLE = jniFunction(env, FIND_CLASS, FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        FROM_REFLECTED_METHOD_HANDLE = jniFunction(env, FROM_REFLECTED_METHOD, FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        NEW_GLOBAL_REF_HANDLE = jniFunction(env, NEW_GLOBAL_REF, FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        EXCEPTION_DESCRIBE_HANDLE = jniFunction(env, EXCEPTION_DESCRIBE, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        EXCEPTION_CLEAR_HANDLE = jniFunction(env, EXCEPTION_CLEAR, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        GET_METHOD_ID_HANDLE = jniFunction(env, GET_METHOD_ID, FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        CALL_OBJECT_METHOD_A_HANDLE = jniFunction(env, CALL_OBJECT_METHOD_A, FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        GET_STATIC_METHOD_ID_HANDLE = jniFunction(env, GET_STATIC_METHOD_ID, FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        CALL_STATIC_OBJECT_METHOD_A_HANDLE = jniFunction(env, CALL_STATIC_OBJECT_METHOD_A, FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        NEW_STRING_UTF_HANDLE = jniFunction(env, NEW_STRING_UTF, FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        REGISTER_NATIVES_HANDLE = jniFunction(env, REGISTER_NATIVES, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        EXCEPTION_CHECK_HANDLE = jniFunction(env, EXCEPTION_CHECK, FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS));
        register(env);
    }

    private MethodAddressResolver() {
    }

    static long methodAddress(Method method) {
        return JniMethodAddressBridge.resolve(method);
    }

    private static void register(MemorySegment env) {
        try {
            MemorySegment bridgeClass = applicationClass(env, BRIDGE_CLASS_NAME);
            MemorySegment upcall = HotSpotMethodBridge.LINKER.upcallStub(
                    MethodHandles.lookup().findStatic(
                            MethodAddressResolver.class,
                            "resolveUpcall",
                            MethodType.methodType(long.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    ARENA);
            MemorySegment methods = ARENA.allocate(ADDRESS_SIZE * 3, ValueLayout.ADDRESS.byteAlignment());
            methods.set(ValueLayout.ADDRESS, 0, ARENA.allocateFrom("resolve"));
            methods.set(ValueLayout.ADDRESS, ADDRESS_SIZE, ARENA.allocateFrom("(Ljava/lang/reflect/Method;)J"));
            methods.set(ValueLayout.ADDRESS, ADDRESS_SIZE * 2, upcall);
            int res = (int) REGISTER_NATIVES_HANDLE.invokeExact(env, bridgeClass, methods, 1);
            checkJni(env, "RegisterNatives");
            if (res != 0) {
                throw new IllegalStateException("RegisterNatives failed: " + res);
            }
        } catch (Throwable e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static MemorySegment applicationClass(MemorySegment env, String name) throws Throwable {
        MemorySegment classClass = findClass(env, "java/lang/Class");
        MemorySegment threadClass = findClass(env, "java/lang/Thread");
        MemorySegment currentThread = getStaticMethod(env, threadClass, "currentThread", "()Ljava/lang/Thread;");
        MemorySegment thread = globalRef(env, (MemorySegment) CALL_STATIC_OBJECT_METHOD_A_HANDLE.invokeExact(env, threadClass, currentThread, MemorySegment.NULL));
        checkJni(env, "Thread.currentThread");
        MemorySegment getContextClassLoader = getMethod(env, threadClass, "getContextClassLoader", "()Ljava/lang/ClassLoader;");
        MemorySegment loader = globalRef(env, (MemorySegment) CALL_OBJECT_METHOD_A_HANDLE.invokeExact(env, thread, getContextClassLoader, MemorySegment.NULL));
        checkJni(env, "Thread.getContextClassLoader");
        MemorySegment forName = getStaticMethod(env, classClass, "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
        MemorySegment args = ARENA.allocate(ADDRESS_SIZE * 3, ValueLayout.ADDRESS.byteAlignment());
        args.set(ValueLayout.ADDRESS, 0, newString(env, name));
        args.set(ValueLayout.JAVA_LONG, ADDRESS_SIZE, 0L);
        args.set(ValueLayout.ADDRESS, ADDRESS_SIZE * 2, loader);
        MemorySegment result = globalRef(env, (MemorySegment) CALL_STATIC_OBJECT_METHOD_A_HANDLE.invokeExact(env, classClass, forName, args));
        checkJni(env, "Class.forName");
        return result;
    }

    private static MemorySegment findClass(MemorySegment env, String name) throws Throwable {
        MemorySegment result = globalRef(env, (MemorySegment) FIND_CLASS_HANDLE.invokeExact(env, ARENA.allocateFrom(name)));
        checkJni(env, "FindClass");
        return result;
    }

    private static MemorySegment getMethod(MemorySegment env, MemorySegment klass, String name, String descriptor) throws Throwable {
        MemorySegment result = (MemorySegment) GET_METHOD_ID_HANDLE.invokeExact(env, klass, ARENA.allocateFrom(name), ARENA.allocateFrom(descriptor));
        checkJni(env, "GetMethodID");
        return result;
    }

    private static MemorySegment getStaticMethod(MemorySegment env, MemorySegment klass, String name, String descriptor) throws Throwable {
        MemorySegment result = (MemorySegment) GET_STATIC_METHOD_ID_HANDLE.invokeExact(env, klass, ARENA.allocateFrom(name), ARENA.allocateFrom(descriptor));
        checkJni(env, "GetStaticMethodID");
        return result;
    }

    private static MemorySegment newString(MemorySegment env, String value) throws Throwable {
        MemorySegment result = globalRef(env, (MemorySegment) NEW_STRING_UTF_HANDLE.invokeExact(env, ARENA.allocateFrom(value)));
        checkJni(env, "NewStringUTF");
        return result;
    }

    private static MemorySegment globalRef(MemorySegment env, MemorySegment ref) throws Throwable {
        return (MemorySegment) NEW_GLOBAL_REF_HANDLE.invokeExact(env, ref);
    }

    private static long resolveUpcall(MemorySegment env, MemorySegment self, MemorySegment reflectedMethod) {
        try {
            MemorySegment jmethodId = (MemorySegment) FROM_REFLECTED_METHOD_HANDLE.invokeExact(env, reflectedMethod);
            MemorySegment method = (MemorySegment) CHECKED_RESOLVE_JMETHOD_ID.invokeExact(jmethodId);
            return method.address();
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private static MethodHandle jniFunction(MemorySegment env, int index, FunctionDescriptor descriptor) {
        long functions = HotSpotMethodBridge.getAddress(env.address());
        long function = HotSpotMethodBridge.getAddress(functions + (long) index * ValueLayout.ADDRESS.byteSize());
        return HotSpotMethodBridge.LINKER.downcallHandle(MemorySegment.ofAddress(function), descriptor);
    }

    private static void checkJni(MemorySegment env, String op) throws Throwable {
        byte exception = (byte) EXCEPTION_CHECK_HANDLE.invokeExact(env);
        if (exception == 0) {
            return;
        }
        EXCEPTION_DESCRIBE_HANDLE.invokeExact(env);
        EXCEPTION_CLEAR_HANDLE.invokeExact(env);
        throw new IllegalStateException(op + " raised a JNI exception");
    }

    private static MemorySegment currentJniEnv() {
        try {
            MemorySegment vmBuf = ARENA.allocate(ValueLayout.ADDRESS);
            MemorySegment countBuf = ARENA.allocate(ValueLayout.JAVA_INT);
            int res = (int) JNI_GET_CREATED_JAVA_VMS.invokeExact(vmBuf, 1, countBuf);
            if (res != 0 || countBuf.get(ValueLayout.JAVA_INT, 0) != 1) {
                throw new IllegalStateException("JNI_GetCreatedJavaVMs failed: " + res);
            }

            MemorySegment vm = vmBuf.get(ValueLayout.ADDRESS, 0);
            long functions = HotSpotMethodBridge.getAddress(vm.address());
            long getEnv = HotSpotMethodBridge.getAddress(functions + 6L * ValueLayout.ADDRESS.byteSize());
            MethodHandle getEnvHandle = HotSpotMethodBridge.LINKER.downcallHandle(
                    MemorySegment.ofAddress(getEnv),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            MemorySegment envBuf = ARENA.allocate(ValueLayout.ADDRESS);
            int getEnvRes = (int) getEnvHandle.invokeExact(vm, envBuf, JNI_VERSION_1_8);
            if (getEnvRes != 0) {
                throw new IllegalStateException("JavaVM.GetEnv failed: " + getEnvRes);
            }
            return envBuf.get(ValueLayout.ADDRESS, 0);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
