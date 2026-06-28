package top.dreamlike.jdk25.openlink;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.LockSupport;

public final class HotSpot25Linker {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final long ADDRESS_SIZE = ValueLayout.ADDRESS.byteSize();
    private static final int JNI_VERSION_1_8 = 0x0001_0008;
    private static final int METHOD_FLAGS_TARGET_BITS = (1 << 8) | (1 << 9) | (1 << 10) | (1 << 12);
    private static final VarHandle INT_HANDLE = ValueLayout.JAVA_INT.varHandle();
    private static final NMethodLayout N_METHOD_LAYOUT = NMethodLayout.load();
    private static final MethodHandle JNI_GET_CREATED_JAVA_VMS = LINKER.downcallHandle(
            SymbolLookup.libraryLookup(VmStructs.libjvm(), Arena.global())
                    .find("JNI_GetCreatedJavaVMs")
                    .orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    private HotSpot25Linker() {
    }

    public static Link linkToCarrier(Method target, Method carrier) {
        long targetMethod = methodAddress(target);
        long carrierMethod = methodAddress(carrier);

        long carrierCode = getAddress(carrierMethod + N_METHOD_LAYOUT.methodCodeOffset);
        long carrierCompiledEntry = getAddress(carrierMethod + N_METHOD_LAYOUT.methodCompiledEntryOffset);
        long carrierInterpretedEntry = getAddress(carrierMethod + N_METHOD_LAYOUT.methodInterpretedEntryOffset);
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
        putAddress(targetMethod + N_METHOD_LAYOUT.methodCodeOffset, carrierCode);
        VarHandle.storeStoreFence();
        putAddress(targetMethod + N_METHOD_LAYOUT.methodCompiledEntryOffset, carrierCompiledEntry);
        VarHandle.storeStoreFence();
        putAddress(targetMethod + N_METHOD_LAYOUT.methodInterpretedEntryOffset, carrierInterpretedEntry);

        return new Link(targetMethod, carrierMethod, carrierCode, carrierCompiledEntry, carrierInterpretedEntry);
    }

    private static void suppressTargetCompilation(long targetMethod) {
        long flagsAddress = targetMethod + N_METHOD_LAYOUT.methodFlagsOffset;
        INT_HANDLE.getAndBitwiseOr(memory(flagsAddress, Integer.BYTES), 0L, METHOD_FLAGS_TARGET_BITS);
    }

    public static boolean isCurrent(Link link) {
        return getAddress(link.targetMethod + N_METHOD_LAYOUT.methodCodeOffset) == link.code
                && getAddress(link.targetMethod + N_METHOD_LAYOUT.methodCompiledEntryOffset) == link.compiledEntry
                && getAddress(link.targetMethod + N_METHOD_LAYOUT.methodInterpretedEntryOffset) == link.interpretedEntry
                && getAddress(link.carrierMethod + N_METHOD_LAYOUT.methodCodeOffset) == link.code
                && getAddress(link.carrierMethod + N_METHOD_LAYOUT.methodCompiledEntryOffset) == link.compiledEntry
                && getAddress(link.carrierMethod + N_METHOD_LAYOUT.methodInterpretedEntryOffset) == link.interpretedEntry;
    }

    public static boolean compileNow(Method method, int level) {
        return Hidden.compile(methodAddress(method), level);
    }

    // jit可能是异步的 但是你可以参考top.dreamlike.jdk25.openlink.OpenLinkDemoTest.installCompilerDirectives做个同步的
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
                getAddress(methodAddress + N_METHOD_LAYOUT.methodInterpreterEntryOffset),
                getAddress(methodAddress + N_METHOD_LAYOUT.methodCodeOffset),
                getAddress(methodAddress + N_METHOD_LAYOUT.methodCompiledEntryOffset),
                getAddress(methodAddress + N_METHOD_LAYOUT.methodInterpretedEntryOffset));
    }

    private static long methodAddress(Method method) {
        return NativeBridge.methodAddress(method);
    }

    private static MemorySegment memory(long address, long byteSize) {
        return MemorySegment.ofAddress(address).reinterpret(byteSize);
    }

    private static long getAddress(long address) {
        return memory(address, ADDRESS_SIZE).get(ValueLayout.ADDRESS, 0).address();
    }

    private static void putAddress(long address, long value) {
        memory(address, ADDRESS_SIZE).set(ValueLayout.ADDRESS, 0, value == 0 ? MemorySegment.NULL : MemorySegment.ofAddress(value));
    }

    private static int getInt(long address) {
        return memory(address, Integer.BYTES).get(ValueLayout.JAVA_INT, 0);
    }

    private static long getLong(long address) {
        return memory(address, Long.BYTES).get(ValueLayout.JAVA_LONG, 0);
    }

    private static byte getByte(long address) {
        return memory(address, Byte.BYTES).get(ValueLayout.JAVA_BYTE, 0);
    }

    public record MethodState(long method, long interpreterEntry, long code, long compiledEntry, long interpretedEntry) {
    }

    public record Link(long targetMethod, long carrierMethod, long code, long compiledEntry, long interpretedEntry) {
    }

    private record NMethodLayout(
            long methodFlagsOffset,
            long methodInterpreterEntryOffset,
            long methodCodeOffset,
            long methodCompiledEntryOffset,
            long methodInterpretedEntryOffset) {

        private static NMethodLayout load() {
            VmStructs vm = new VmStructs();
            return new NMethodLayout(
                    vm.offset("Method", "_intrinsic_id") - Integer.BYTES,
                    vm.offset("Method", "_i2i_entry"),
                    vm.offset("Method", "_code"),
                    vm.offset("Method", "_from_compiled_entry"),
                    vm.offset("Method", "_from_interpreted_entry"));
        }
    }

    private static final class VmStructs {
        private static final SymbolLookup JVM = SymbolLookup.libraryLookup(libjvm(), Arena.global());

        private final long entries;
        private final long typeNameOffset;
        private final long fieldNameOffset;
        private final long isStaticOffset;
        private final long offsetOffset;
        private final long stride;

        private VmStructs() {
            entries = getAddress(symbol("gHotSpotVMStructs"));
            typeNameOffset = getLong(symbol("gHotSpotVMStructEntryTypeNameOffset"));
            fieldNameOffset = getLong(symbol("gHotSpotVMStructEntryFieldNameOffset"));
            isStaticOffset = getLong(symbol("gHotSpotVMStructEntryIsStaticOffset"));
            offsetOffset = getLong(symbol("gHotSpotVMStructEntryOffsetOffset"));
            stride = getLong(symbol("gHotSpotVMStructEntryArrayStride"));
        }

        long offset(String typeName, String fieldName) {
            long entry = find(typeName, fieldName);
            return getLong(entry + offsetOffset);
        }

        private long find(String typeName, String fieldName) {
            for (long entry = entries; ; entry += stride) {
                long typePtr = getAddress(entry + typeNameOffset);
                long fieldPtr = getAddress(entry + fieldNameOffset);
                if (typePtr == 0 || fieldPtr == 0) {
                    break;
                }
                if (getInt(entry + isStaticOffset) == 0
                        && typeName.equals(cString(typePtr))
                        && fieldName.equals(cString(fieldPtr))) {
                    return entry;
                }
            }
            throw new IllegalArgumentException("VMStruct not found: " + typeName + "::" + fieldName);
        }

        private static long symbol(String name) {
            MemorySegment segment = JVM.find(name).orElseThrow(() -> new IllegalStateException("symbol not found: " + name));
            return segment.address();
        }

        private static String libjvm() {
            String javaHome = System.getProperty("java.home");
            String mapped = System.mapLibraryName("jvm");
            Path unix = Path.of(javaHome, "lib", "server", mapped);
            if (Files.exists(unix)) {
                return unix.toString();
            }
            Path windows = Path.of(javaHome, "bin", "server", mapped);
            if (Files.exists(windows)) {
                return windows.toString();
            }
            return mapped;
        }

        private static String cString(long address) {
            int length = 0;
            while (getByte(address + length) != 0) {
                length++;
            }
            byte[] bytes = new byte[length];
            for (int i = 0; i < length; i++) {
                bytes[i] = getByte(address + i);
            }
            return new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
        }
    }

    private static final class NativeBridge {
        private static final String BRIDGE_CLASS_NAME = "top.dreamlike.jdk25.openlink.JniMethodAddressBridge";
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
        private static final MethodHandle CHECKED_RESOLVE_JMETHOD_ID = LINKER.downcallHandle(
                MemorySegment.ofAddress(MachO.symbol("__ZN6Method26checked_resolve_jmethod_idEP10_jmethodID")),
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

        private static long methodAddress(Method method) {
            return JniMethodAddressBridge.resolve(method);
        }

        private static void register(MemorySegment env) {
            try {
                MemorySegment bridgeClass = applicationClass(env, BRIDGE_CLASS_NAME);
                MemorySegment upcall = LINKER.upcallStub(
                        MethodHandles.lookup().findStatic(
                                NativeBridge.class,
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
            MemorySegment args = ARENA.allocate(ADDRESS_SIZE * 3, ADDRESS_SIZE);
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
            long functions = getAddress(env.address());
            long function = getAddress(functions + (long) index * ADDRESS_SIZE);
            return LINKER.downcallHandle(MemorySegment.ofAddress(function), descriptor);
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
    }

    private static final class Hidden {
        private static final SymbolLookup JVM = SymbolLookup.libraryLookup(VmStructs.libjvm(), Arena.global());
        private static final int INVOCATION_ENTRY_BCI = -1;

        //todo 尽可能不要依赖于cpp符号 后面看看怎么搞
        private static final MethodHandle JAVA_THREAD_CURRENT = LINKER.downcallHandle(
                MemorySegment.ofAddress(MachO.symbol("__ZN10JavaThread7currentEv")),
                FunctionDescriptor.of(ValueLayout.ADDRESS));
        private static final MethodHandle WHITEBOX_COMPILE_METHOD = LINKER.downcallHandle(
                MemorySegment.ofAddress(MachO.symbol("__ZN8WhiteBox14compile_methodEP6MethodiiP10JavaThread")),
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        private static boolean compile(long method, int level) {
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
    }

    private static MemorySegment currentJniEnv() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment vmBuf = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment countBuf = arena.allocate(ValueLayout.JAVA_INT);
            int res = (int) JNI_GET_CREATED_JAVA_VMS.invokeExact(vmBuf, 1, countBuf);
            if (res != 0 || countBuf.get(ValueLayout.JAVA_INT, 0) != 1) {
                throw new IllegalStateException("JNI_GetCreatedJavaVMs failed: " + res);
            }

            MemorySegment vm = vmBuf.get(ValueLayout.ADDRESS, 0);
            long functions = getAddress(vm.address());
            long getEnv = getAddress(functions + 6L * ADDRESS_SIZE);
            MethodHandle getEnvHandle = LINKER.downcallHandle(
                    MemorySegment.ofAddress(getEnv),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

            MemorySegment envBuf = arena.allocate(ValueLayout.ADDRESS);
            int getEnvRes = (int) getEnvHandle.invokeExact(vm, envBuf, JNI_VERSION_1_8);
            if (getEnvRes != 0) {
                throw new IllegalStateException("JavaVM.GetEnv failed: " + getEnvRes);
            }
            return envBuf.get(ValueLayout.ADDRESS, 0);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private static final class MachO {
        private static final int LC_SYMTAB = 0x2;
        private static final long BASE = runtimeAddress("gHotSpotVMStructs") - fileSymbol("_gHotSpotVMStructs");

        static long symbol(String name) {
            long value = fileSymbol(name);
            if (value == 0) {
                throw new IllegalStateException("Mach-O symbol not found: " + name);
            }
            return BASE + value;
        }

        private static long runtimeAddress(String name) {
            return SymbolLookup.libraryLookup(VmStructs.libjvm(), Arena.global())
                    .find(name)
                    .orElseThrow()
                    .address();
        }

        private static long fileSymbol(String name) {
            try {
                byte[] bytes = Files.readAllBytes(Path.of(VmStructs.libjvm()));
                ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                if (bb.getInt(0) != 0xfeedfacf) {
                    throw new IllegalStateException("only little-endian Mach-O 64 is implemented");
                }
                int ncmds = bb.getInt(16);
                int command = 32;
                int symoff = 0;
                int nsyms = 0;
                int stroff = 0;
                for (int i = 0; i < ncmds; i++) {
                    int cmd = bb.getInt(command);
                    int cmdsize = bb.getInt(command + 4);
                    if (cmd == LC_SYMTAB) {
                        symoff = bb.getInt(command + 8);
                        nsyms = bb.getInt(command + 12);
                        stroff = bb.getInt(command + 16);
                        break;
                    }
                    command += cmdsize;
                }
                for (int i = 0; i < nsyms; i++) {
                    int entry = symoff + i * 16;
                    int strx = bb.getInt(entry);
                    if (name.equals(cString(bytes, stroff + strx))) {
                        return bb.getLong(entry + 8);
                    }
                }
                return 0;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private static String cString(byte[] bytes, int offset) {
            int end = offset;
            while (bytes[end] != 0) {
                end++;
            }
            return new String(bytes, offset, end - offset, java.nio.charset.StandardCharsets.US_ASCII);
        }
    }
}
