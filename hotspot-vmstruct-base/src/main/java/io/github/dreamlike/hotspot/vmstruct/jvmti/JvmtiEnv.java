package io.github.dreamlike.hotspot.vmstruct.jvmti;

import io.github.dreamlike.hotspot.vmstruct.HotSpotMemory;
import io.github.dreamlike.hotspot.vmstruct.jni.JNIEnv;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public final class JvmtiEnv {
    public static final int JVMTI_ENABLE = 1;
    public static final int JVMTI_DISABLE = 0;
    public static final int JVMTI_MIN_EVENT_TYPE_VAL = 50;
    public static final int JVMTI_EVENT_CLASS_FILE_LOAD_HOOK = 54;
    public static final int JVMTI_EVENT_CALLBACK_COUNT = 39;
    public static final int JVMTI_ERROR_NONE = 0;

    public static final int JVMTI_CAPABILITIES_WORDS = 4;

    private static final int JVMTI_VERSION_1_1 = 0x30010100;
    private static final int JAVA_VM_GET_ENV_NUMBER = 7;
    private static final int SET_EVENT_NOTIFICATION_MODE_NUMBER = 2;
    private static final int GET_NAMED_MODULE_NUMBER = 40;
    private static final int ALLOCATE_NUMBER = 46;
    private static final int GET_CAPABILITIES_NUMBER = 89;
    private static final int SET_EVENT_CALLBACKS_NUMBER = 122;
    private static final int GET_POTENTIAL_CAPABILITIES_NUMBER = 140;
    private static final int ADD_CAPABILITIES_NUMBER = 142;
    private static final int RETRANSFORM_CLASSES_NUMBER = 152;

    private static final MethodHandle JAVA_VM_GET_ENV = downcall(
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)
    );
    private static final MethodHandle SET_EVENT_NOTIFICATION_MODE = downcall(
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS)
    );
    private static final MethodHandle GET_NAMED_MODULE = downcall(
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS)
    );
    private static final MethodHandle ALLOCATE = downcall(
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS)
    );
    private static final MethodHandle GET_CAPABILITIES = downcall(
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)
    );
    private static final MethodHandle SET_EVENT_CALLBACKS = downcall(
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)
    );
    private static final MethodHandle GET_POTENTIAL_CAPABILITIES = downcall(
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)
    );
    private static final MethodHandle ADD_CAPABILITIES = downcall(
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)
    );
    private static final MethodHandle RETRANSFORM_CLASSES = downcall(
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS)
    );

    private final MemorySegment pointer;

    private JvmtiEnv(MemorySegment pointer) {
        if (isNull(pointer)) {
            throw new IllegalArgumentException("jvmtiEnv pointer is NULL");
        }
        this.pointer = pointer.reinterpret(Long.MAX_VALUE);
    }

    public static JvmtiEnv of(MemorySegment pointer) {
        return new JvmtiEnv(pointer);
    }

    public static JvmtiEnv current() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment envOut = arena.allocate(ADDRESS);
            int result = (int) JAVA_VM_GET_ENV.invokeExact(
                    javaVmFunction(JAVA_VM_GET_ENV_NUMBER),
                    JNIEnv.javaVM(),
                    envOut,
                    JVMTI_VERSION_1_1
            );
            if (result != JNIEnv.JNI_OK) {
                throw new IllegalStateException("JavaVM::GetEnv(JVMTI) failed: " + result);
            }
            MemorySegment env = envOut.get(ADDRESS, 0);
            if (isNull(env)) {
                throw new IllegalStateException("JavaVM::GetEnv(JVMTI) returned NULL");
            }
            return new JvmtiEnv(env);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public MemorySegment pointer() {
        return pointer;
    }

    public MemorySegment allocateCapabilities(Arena arena) {
        return arena.allocate(JAVA_INT, JVMTI_CAPABILITIES_WORDS).fill((byte) 0);
    }

    public MemorySegment getPotentialCapabilities(Arena arena) {
        try {
            MemorySegment capabilities = allocateCapabilities(arena);
            int result = (int) GET_POTENTIAL_CAPABILITIES.invokeExact(
                    function(GET_POTENTIAL_CAPABILITIES_NUMBER),
                    pointer,
                    capabilities
            );
            check("GetPotentialCapabilities", result);
            return capabilities;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public MemorySegment getCapabilities(Arena arena) {
        try {
            MemorySegment capabilities = allocateCapabilities(arena);
            int result = (int) GET_CAPABILITIES.invokeExact(
                    function(GET_CAPABILITIES_NUMBER),
                    pointer,
                    capabilities
            );
            check("GetCapabilities", result);
            return capabilities;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void addCapabilities(MemorySegment capabilities) {
        try {
            int result = (int) ADD_CAPABILITIES.invokeExact(
                    function(ADD_CAPABILITIES_NUMBER),
                    pointer,
                    capabilities
            );
            check("AddCapabilities", result);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void setEventCallbacks(MemorySegment callbacks, long byteSize) {
        try {
            int result = (int) SET_EVENT_CALLBACKS.invokeExact(
                    function(SET_EVENT_CALLBACKS_NUMBER),
                    pointer,
                    callbacks,
                    Math.toIntExact(byteSize)
            );
            check("SetEventCallbacks", result);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void setEventNotificationMode(int mode, int eventType, MemorySegment eventThread) {
        try {
            MemorySegment threadArg = isNull(eventThread) ? MemorySegment.NULL : eventThread;
            int result = (int) SET_EVENT_NOTIFICATION_MODE.invokeExact(
                    function(SET_EVENT_NOTIFICATION_MODE_NUMBER),
                    pointer,
                    mode,
                    eventType,
                    threadArg
            );
            check("SetEventNotificationMode(" + eventType + ")", result);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * 返回 JVMTI {@code GetNamedModule} 产生的 JNI local {@code java.lang.Module} handle。
     *
     * <p>这个返回值只应在当前 JVMTI callback/JNI native frame 内立即消费，不要保存，
     * 也不要在普通 Java 代码里把 direct FFM downcall 返回的 local handle 当稳定引用。
     */
    public MemorySegment getNamedModule(MemorySegment loader, MemorySegment packageName, Arena arena) {
        try {
            MemorySegment moduleOut = arena.allocate(ADDRESS);
            MemorySegment loaderArg = isNull(loader) ? MemorySegment.NULL : loader;
            int result = (int) GET_NAMED_MODULE.invokeExact(
                    function(GET_NAMED_MODULE_NUMBER),
                    pointer,
                    loaderArg,
                    packageName,
                    moduleOut
            );
            check("GetNamedModule", result);
            return moduleOut.get(ADDRESS, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public MemorySegment allocate(long byteSize, Arena scratch) {
        try {
            MemorySegment out = scratch.allocate(ADDRESS);
            int result = (int) ALLOCATE.invokeExact(
                    function(ALLOCATE_NUMBER),
                    pointer,
                    byteSize,
                    out
            );
            check("Allocate", result);
            MemorySegment memory = out.get(ADDRESS, 0);
            if (isNull(memory)) {
                throw new OutOfMemoryError("JVMTI Allocate returned NULL");
            }
            return memory.reinterpret(byteSize);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void retransformClasses(MemorySegment classes, int count) {
        try {
            int result = (int) RETRANSFORM_CLASSES.invokeExact(
                    function(RETRANSFORM_CLASSES_NUMBER),
                    pointer,
                    count,
                    classes
            );
            check("RetransformClasses", result);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static boolean capability(MemorySegment capabilities, int word, int bit) {
        return (capabilities.get(JAVA_INT, (long) word * Integer.BYTES) & (1 << bit)) != 0;
    }

    public static void setCapability(MemorySegment capabilities, int word, int bit) {
        long offset = (long) word * Integer.BYTES;
        capabilities.set(JAVA_INT, offset, capabilities.get(JAVA_INT, offset) | (1 << bit));
    }

    private MemorySegment function(int number) {
        long byteSize = (long) number * HotSpotMemory.ADDRESS_SIZE;
        MemorySegment table = pointer.get(ADDRESS, 0).reinterpret(byteSize);
        return table.get(ADDRESS, (long) (number - 1) * HotSpotMemory.ADDRESS_SIZE);
    }

    private static MemorySegment javaVmFunction(int number) {
        long byteSize = (long) number * HotSpotMemory.ADDRESS_SIZE;
        MemorySegment table = JNIEnv.javaVM().get(ADDRESS, 0).reinterpret(byteSize);
        return table.get(ADDRESS, (long) (number - 1) * HotSpotMemory.ADDRESS_SIZE);
    }

    private static void check(String operation, int errorCode) {
        if (errorCode != JVMTI_ERROR_NONE) {
            throw new JvmtiException(operation, errorCode);
        }
    }

    private static MethodHandle downcall(FunctionDescriptor descriptor) {
        return HotSpotMemory.LINKER.downcallHandle(descriptor);
    }

    private static boolean isNull(MemorySegment segment) {
        return segment == MemorySegment.NULL || segment.address() == 0;
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
