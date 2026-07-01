package io.github.dreamlike.hotspot.classes;

import io.github.dreamlike.hotspot.methodbridge.HotSpotGlobalRefs;
import io.github.dreamlike.hotspot.methodbridge.HotSpotMethodBridge;
import io.github.dreamlike.hotspot.vmstruct.HotSpotMemory;
import io.github.dreamlike.hotspot.vmstruct.jni.GlobalRef;
import io.github.dreamlike.hotspot.vmstruct.jni.JNIEnv;
import io.github.dreamlike.hotspot.vmstruct.jvmti.JvmtiEnv;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.instrument.ClassFileTransformer;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * 不挂 javaagent 的类文件转换实验入口。
 *
 * <p>这里直接通过 FFM 调 JVMTI {@code ClassFileLoadHook} 和
 * {@code RetransformClasses}。第一版所有注册的 transformer 都按
 * retransform-capable 处理：新类加载和显式 {@link #retransform(Class[])}
 * 都会调用同一条 transformer 链。
 */
public final class HotSpotClasses {
    private static final int CAN_GENERATE_ALL_CLASS_HOOK_EVENTS_WORD = 0;
    private static final int CAN_GENERATE_ALL_CLASS_HOOK_EVENTS_BIT = 26;
    private static final int CAN_RETRANSFORM_CLASSES_WORD = 1;
    private static final int CAN_RETRANSFORM_CLASSES_BIT = 5;

    private static final Object INSTALL_LOCK = new Object();
    private static final CopyOnWriteArrayList<ClassFileTransformer> TRANSFORMERS = new CopyOnWriteArrayList<>();
    private static final Arena CALLBACK_ARENA = Arena.global();
    private static final ThreadLocal<Boolean> IN_TRANSFORM = ThreadLocal.withInitial(() -> false);
    private static final JvmtiEnv JVMTI;
    private static final HotSpotMethodBridge.NativeFunctionLink BOOT_LOADER_CLASS_LINK;
    private static final HotSpotMethodBridge.NativeFunctionLink BOOT_UNNAMED_MODULE_LINK;
    private static final Module BOOT_UNNAMED_MODULE;

    private static volatile boolean hookEnabled;
    private static MemorySegment callbacks;
    private static MemorySegment hookStub;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            lookup.ensureInitialized(HotSpotGlobalRefs.class);
            JVMTI = JvmtiEnv.current();
            BootModuleNatives bootModuleNatives = registerBootModuleNatives(lookup);
            BOOT_LOADER_CLASS_LINK = bootModuleNatives.bootLoaderClassLink();
            BOOT_UNNAMED_MODULE_LINK = bootModuleNatives.bootUnnamedModuleLink();
            BOOT_UNNAMED_MODULE = bootUnnamedModule();
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private HotSpotClasses() {
    }

    public static TransformerRegistration transformerRegister(ClassFileTransformer transformer) {
        Objects.requireNonNull(transformer, "transformer");
        synchronized (INSTALL_LOCK) {
            JvmtiEnv env = ensureInstalledLocked();
            TRANSFORMERS.add(transformer);
            if (!hookEnabled) {
                setClassFileLoadHookEnabled(env, true);
                hookEnabled = true;
            }
        }
        return new RegisteredTransformer(transformer);
    }

    public static void retransform(Class<?>... classes) {
        Objects.requireNonNull(classes, "classes");
        if (classes.length == 0) {
            return;
        }
        JvmtiEnv env = ensureInstalled();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment classArray = arena.allocate(ADDRESS, classes.length);
            GlobalRef[] refs = new GlobalRef[classes.length];
            try {
                for (int i = 0; i < classes.length; i++) {
                    Class<?> clazz = Objects.requireNonNull(classes[i], "classes[" + i + "]");
                    refs[i] = HotSpotGlobalRefs.newGlobalRef(clazz);
                    classArray.set(ADDRESS, (long) i * HotSpotMemory.ADDRESS_SIZE, refs[i].ref());
                }
                env.retransformClasses(classArray, classes.length);
            } finally {
                for (GlobalRef ref : refs) {
                    if (ref != null) {
                        ref.close();
                    }
                }
            }
        }
    }

    public interface TransformerRegistration extends AutoCloseable {
        @Override
        void close();
    }

    private static final class RegisteredTransformer implements TransformerRegistration {
        private final ClassFileTransformer transformer;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RegisteredTransformer(ClassFileTransformer transformer) {
            this.transformer = transformer;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            synchronized (INSTALL_LOCK) {
                TRANSFORMERS.remove(transformer);
                if (hookEnabled && TRANSFORMERS.isEmpty()) {
                    setClassFileLoadHookEnabled(JVMTI, false);
                    hookEnabled = false;
                }
            }
        }
    }

    private static JvmtiEnv ensureInstalled() {
        if (callbacks != null) {
            return JVMTI;
        }
        synchronized (INSTALL_LOCK) {
            return ensureInstalledLocked();
        }
    }

    private static JvmtiEnv ensureInstalledLocked() {
        if (callbacks != null) {
            return JVMTI;
        }
        addClassFileHookCapabilities(JVMTI);
        installCallbacks(JVMTI);
        return JVMTI;
    }

    private static void addClassFileHookCapabilities(JvmtiEnv env) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment potential = env.getPotentialCapabilities(arena);
            if (!JvmtiEnv.capability(potential, CAN_GENERATE_ALL_CLASS_HOOK_EVENTS_WORD,
                    CAN_GENERATE_ALL_CLASS_HOOK_EVENTS_BIT)) {
                throw new UnsupportedOperationException("JVMTI can_generate_all_class_hook_events is not available");
            }
            if (!JvmtiEnv.capability(potential, CAN_RETRANSFORM_CLASSES_WORD, CAN_RETRANSFORM_CLASSES_BIT)) {
                throw new UnsupportedOperationException("JVMTI can_retransform_classes is not available");
            }

            MemorySegment desired = env.allocateCapabilities(arena);
            JvmtiEnv.setCapability(desired, CAN_GENERATE_ALL_CLASS_HOOK_EVENTS_WORD,
                    CAN_GENERATE_ALL_CLASS_HOOK_EVENTS_BIT);
            JvmtiEnv.setCapability(desired, CAN_RETRANSFORM_CLASSES_WORD, CAN_RETRANSFORM_CLASSES_BIT);
            env.addCapabilities(desired);

            MemorySegment current = env.getCapabilities(arena);
            if (!JvmtiEnv.capability(current, CAN_GENERATE_ALL_CLASS_HOOK_EVENTS_WORD,
                    CAN_GENERATE_ALL_CLASS_HOOK_EVENTS_BIT)
                    || !JvmtiEnv.capability(current, CAN_RETRANSFORM_CLASSES_WORD,
                    CAN_RETRANSFORM_CLASSES_BIT)) {
                throw new UnsupportedOperationException("JVMTI capabilities were not installed");
            }
        }
    }

    private static void setClassFileLoadHookEnabled(JvmtiEnv env, boolean enabled) {
        env.setEventNotificationMode(
                enabled ? JvmtiEnv.JVMTI_ENABLE : JvmtiEnv.JVMTI_DISABLE,
                JvmtiEnv.JVMTI_EVENT_CLASS_FILE_LOAD_HOOK,
                MemorySegment.NULL
        );
    }

    private static void installCallbacks(JvmtiEnv env) {
        try {
            MethodHandle hook = MethodHandles.lookup().findStatic(
                    HotSpotClasses.class,
                    "onClassFileLoadHook",
                    MethodType.methodType(
                            void.class,
                            MemorySegment.class,
                            MemorySegment.class,
                            MemorySegment.class,
                            MemorySegment.class,
                            MemorySegment.class,
                            MemorySegment.class,
                            int.class,
                            MemorySegment.class,
                            MemorySegment.class,
                            MemorySegment.class
                    )
            );
            hookStub = HotSpotMemory.LINKER.upcallStub(
                    hook,
                    FunctionDescriptor.ofVoid(
                            ADDRESS,
                            ADDRESS,
                            ADDRESS,
                            ADDRESS,
                            ADDRESS,
                            ADDRESS,
                            JAVA_INT,
                            ADDRESS,
                            ADDRESS,
                            ADDRESS
                    ),
                    CALLBACK_ARENA
            );
            callbacks = CALLBACK_ARENA.allocate(ADDRESS, JvmtiEnv.JVMTI_EVENT_CALLBACK_COUNT).fill((byte) 0);
            long classFileLoadHookOffset = (long) (JvmtiEnv.JVMTI_EVENT_CLASS_FILE_LOAD_HOOK
                    - JvmtiEnv.JVMTI_MIN_EVENT_TYPE_VAL) * HotSpotMemory.ADDRESS_SIZE;
            callbacks.set(ADDRESS, classFileLoadHookOffset, hookStub);
            env.setEventCallbacks(callbacks, callbacks.byteSize());
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void onClassFileLoadHook(
            MemorySegment callbackJvmti,
            MemorySegment jniEnv,
            MemorySegment classBeingRedefined,
            MemorySegment loader,
            MemorySegment name,
            MemorySegment protectionDomain,
            int classDataLen,
            MemorySegment classData,
            MemorySegment newClassDataLen,
            MemorySegment newClassData) {
        if (IN_TRANSFORM.get()) {
            return;
        }
        IN_TRANSFORM.set(true);
        try {
            transform(callbackJvmti, jniEnv, classBeingRedefined, loader, name, protectionDomain,
                    classDataLen, classData, newClassDataLen, newClassData);
        } catch (Throwable ignored) {
            // JVMTI callbacks must not leak Java exceptions through the native upcall boundary.
        } finally {
            IN_TRANSFORM.set(false);
        }
    }

    private static void transform(
            MemorySegment callbackJvmti,
            MemorySegment jniEnv,
            MemorySegment classBeingRedefined,
            MemorySegment loader,
            MemorySegment name,
            MemorySegment protectionDomain,
            int classDataLen,
            MemorySegment classData,
            MemorySegment newClassDataLen,
            MemorySegment newClassData) {
        if (TRANSFORMERS.isEmpty()) {
            return;
        }

        Class<?> redefiningClass = fromJObject(classBeingRedefined, Class.class);
        ClassLoader classLoader = fromJObject(loader, ClassLoader.class);
        ProtectionDomain domain = fromJObject(protectionDomain, ProtectionDomain.class);
        String className = readClassName(name);
        byte[] input = classData.reinterpret(classDataLen).toArray(JAVA_BYTE);
        JvmtiEnv callbackEnv = JvmtiEnv.of(callbackJvmti);
        Module module = resolveModule(callbackEnv, redefiningClass, loader, classLoader, className);

        byte[] output = runTransformers(module, classLoader, className, redefiningClass, domain, input);
        if (output == null) {
            return;
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outputMemory = callbackEnv.allocate(output.length, arena);
            outputMemory.copyFrom(MemorySegment.ofArray(output));
            newClassDataLen.reinterpret(JAVA_INT.byteSize()).set(JAVA_INT, 0, output.length);
            newClassData.reinterpret(ADDRESS.byteSize()).set(ADDRESS, 0, outputMemory);
        }
    }

    private static byte[] runTransformers(
            Module module,
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        boolean transformed = false;
        byte[] current = classfileBuffer;
        for (ClassFileTransformer transformer : TRANSFORMERS) {
            try {
                byte[] next = transformer.transform(
                        module,
                        loader,
                        className,
                        classBeingRedefined,
                        protectionDomain,
                        current
                );
                if (next != null) {
                    transformed = true;
                    current = next;
                }
            } catch (Throwable ignored) {
                // Same policy as java.lang.instrument: one broken transformer does not stop the rest.
            }
        }
        return transformed ? current : null;
    }

    private static Module resolveModule(
            JvmtiEnv callbackEnv,
            Class<?> classBeingRedefined,
            MemorySegment loaderHandle,
            ClassLoader loader,
            String className) {
        if (classBeingRedefined != null) {
            return classBeingRedefined.getModule();
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment packageName = arena.allocateFrom(packageName(className));
            MemorySegment module = callbackEnv.getNamedModule(loaderHandle, packageName, arena);
            if (!isNull(module)) {
                return fromJObject(module, Module.class);
            }
        }

        if (loader != null) {
            return loader.getUnnamedModule();
        }
        return BOOT_UNNAMED_MODULE;
    }

    private static BootModuleNatives registerBootModuleNatives(MethodHandles.Lookup lookup) throws ReflectiveOperationException {
        MethodHandle bootLoaderClass = lookup.findStatic(
                HotSpotClasses.class,
                "bootLoaderClass0Impl",
                MethodType.methodType(MemorySegment.class, MemorySegment.class, MemorySegment.class)
        );
        HotSpotMethodBridge.NativeFunctionLink bootLoaderClassLink = registerNative(
                "bootLoaderClass0",
                MethodType.methodType(Class.class),
                bootLoaderClass,
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS)
        );

        MethodHandle bootUnnamedModule = lookup.findStatic(
                HotSpotClasses.class,
                "bootUnnamedModule0Impl",
                MethodType.methodType(MemorySegment.class, MemorySegment.class, MemorySegment.class, long.class)
        );
        HotSpotMethodBridge.NativeFunctionLink bootUnnamedModuleLink = registerNative(
                "bootUnnamedModule0",
                MethodType.methodType(Module.class, long.class),
                bootUnnamedModule,
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG)
        );
        return new BootModuleNatives(bootLoaderClassLink, bootUnnamedModuleLink);
    }

    private static HotSpotMethodBridge.NativeFunctionLink registerNative(
            String name,
            MethodType nativeType,
            MethodHandle implementation,
            FunctionDescriptor descriptor) throws ReflectiveOperationException {
        Method nativeMethod = HotSpotClasses.class.getDeclaredMethod(name, nativeType.parameterArray());
        MemorySegment stub = HotSpotMemory.LINKER.upcallStub(implementation, descriptor, CALLBACK_ARENA);
        return HotSpotMethodBridge.registerNative(nativeMethod, stub);
    }

    private static Module bootUnnamedModule() {
        // 不把 FindClass 返回的 local jclass 跨 FFM downcall 继续使用。
        // 先让 native wrapper 把 local ref 正常转换成 Java Class，再临时转成 global jclass。
        Class<?> bootLoaderClass = bootLoaderClass0();
        try (GlobalRef bootLoaderClassRef = HotSpotGlobalRefs.newGlobalRef(bootLoaderClass)) {
            return bootUnnamedModule0(bootLoaderClassRef.address());
        }
    }

    private static native Class<?> bootLoaderClass0();

    private static native Module bootUnnamedModule0(long bootLoaderClassRef);

    private static MemorySegment bootLoaderClass0Impl(MemorySegment envPointer, MemorySegment clazz) {
        return JNIEnv.of(envPointer).findClassLocal("jdk/internal/loader/BootLoader");
    }

    private static MemorySegment bootUnnamedModule0Impl(
            MemorySegment envPointer,
            MemorySegment clazz,
            long bootLoaderClassRef) {
        JNIEnv env = JNIEnv.of(envPointer);
        MemorySegment bootLoaderClass = MemorySegment.ofAddress(bootLoaderClassRef);
        MemorySegment methodId = env.getStaticMethodId(
                bootLoaderClass,
                "getUnnamedModule",
                "()Ljava/lang/Module;"
        );
        return env.callStaticObjectMethodA(bootLoaderClass, methodId, MemorySegment.NULL);
    }

    private static String packageName(String className) {
        if (className == null) {
            return "";
        }
        int lastSlash = className.lastIndexOf('/');
        return lastSlash < 0 ? "" : className.substring(0, lastSlash);
    }

    private static String readClassName(MemorySegment name) {
        if (isNull(name)) {
            return null;
        }
        return name.reinterpret(Long.MAX_VALUE).getString(0, UTF_8);
    }

    private static <T> T fromJObject(MemorySegment handle, Class<T> type) {
        if (isNull(handle)) {
            return null;
        }
        Object object = HotSpotGlobalRefs.fromJObject(handle.address());
        return type.cast(object);
    }

    private static boolean isNull(MemorySegment segment) {
        return segment == null || segment.address() == 0;
    }

    private record BootModuleNatives(
            HotSpotMethodBridge.NativeFunctionLink bootLoaderClassLink,
            HotSpotMethodBridge.NativeFunctionLink bootUnnamedModuleLink) {
    }
}
