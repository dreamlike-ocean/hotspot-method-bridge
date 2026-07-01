package io.github.dreamlike.hotspot.vmstruct.jvmti;

public final class JvmtiException extends RuntimeException {
    private final int errorCode;

    JvmtiException(String operation, int errorCode) {
        super(operation + " failed: " + errorName(errorCode) + " (" + errorCode + ")");
        this.errorCode = errorCode;
    }

    public int errorCode() {
        return errorCode;
    }

    private static String errorName(int errorCode) {
        return switch (errorCode) {
            case 0 -> "JVMTI_ERROR_NONE";
            case 10 -> "JVMTI_ERROR_INVALID_THREAD";
            case 11 -> "JVMTI_ERROR_INVALID_THREAD_GROUP";
            case 12 -> "JVMTI_ERROR_INVALID_PRIORITY";
            case 13 -> "JVMTI_ERROR_THREAD_NOT_SUSPENDED";
            case 14 -> "JVMTI_ERROR_THREAD_SUSPENDED";
            case 15 -> "JVMTI_ERROR_THREAD_NOT_ALIVE";
            case 20 -> "JVMTI_ERROR_INVALID_OBJECT";
            case 21 -> "JVMTI_ERROR_INVALID_CLASS";
            case 22 -> "JVMTI_ERROR_CLASS_NOT_PREPARED";
            case 23 -> "JVMTI_ERROR_INVALID_METHODID";
            case 24 -> "JVMTI_ERROR_INVALID_LOCATION";
            case 25 -> "JVMTI_ERROR_INVALID_FIELDID";
            case 31 -> "JVMTI_ERROR_NO_MORE_FRAMES";
            case 32 -> "JVMTI_ERROR_OPAQUE_FRAME";
            case 33 -> "JVMTI_ERROR_TYPE_MISMATCH";
            case 34 -> "JVMTI_ERROR_INVALID_SLOT";
            case 35 -> "JVMTI_ERROR_DUPLICATE";
            case 40 -> "JVMTI_ERROR_NOT_FOUND";
            case 50 -> "JVMTI_ERROR_INVALID_MONITOR";
            case 51 -> "JVMTI_ERROR_NOT_MONITOR_OWNER";
            case 52 -> "JVMTI_ERROR_INTERRUPT";
            case 60 -> "JVMTI_ERROR_INVALID_CLASS_FORMAT";
            case 61 -> "JVMTI_ERROR_CIRCULAR_CLASS_DEFINITION";
            case 62 -> "JVMTI_ERROR_FAILS_VERIFICATION";
            case 63 -> "JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED";
            case 64 -> "JVMTI_ERROR_UNSUPPORTED_REDEFINITION_SCHEMA_CHANGED";
            case 65 -> "JVMTI_ERROR_INVALID_TYPESTATE";
            case 66 -> "JVMTI_ERROR_UNSUPPORTED_REDEFINITION_HIERARCHY_CHANGED";
            case 67 -> "JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_DELETED";
            case 68 -> "JVMTI_ERROR_UNSUPPORTED_VERSION";
            case 69 -> "JVMTI_ERROR_NAMES_DONT_MATCH";
            case 70 -> "JVMTI_ERROR_UNSUPPORTED_REDEFINITION_CLASS_MODIFIERS_CHANGED";
            case 71 -> "JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_MODIFIERS_CHANGED";
            case 72 -> "JVMTI_ERROR_UNSUPPORTED_REDEFINITION_CLASS_ATTRIBUTE_CHANGED";
            case 73 -> "JVMTI_ERROR_UNSUPPORTED_OPERATION";
            case 79 -> "JVMTI_ERROR_UNMODIFIABLE_CLASS";
            case 80 -> "JVMTI_ERROR_UNMODIFIABLE_MODULE";
            case 98 -> "JVMTI_ERROR_NOT_AVAILABLE";
            case 99 -> "JVMTI_ERROR_MUST_POSSESS_CAPABILITY";
            case 100 -> "JVMTI_ERROR_NULL_POINTER";
            case 101 -> "JVMTI_ERROR_ABSENT_INFORMATION";
            case 102 -> "JVMTI_ERROR_INVALID_EVENT_TYPE";
            case 103 -> "JVMTI_ERROR_ILLEGAL_ARGUMENT";
            case 104 -> "JVMTI_ERROR_NATIVE_METHOD";
            case 106 -> "JVMTI_ERROR_CLASS_LOADER_UNSUPPORTED";
            case 110 -> "JVMTI_ERROR_OUT_OF_MEMORY";
            case 111 -> "JVMTI_ERROR_ACCESS_DENIED";
            case 112 -> "JVMTI_ERROR_WRONG_PHASE";
            case 113 -> "JVMTI_ERROR_INTERNAL";
            case 115 -> "JVMTI_ERROR_UNATTACHED_THREAD";
            case 116 -> "JVMTI_ERROR_INVALID_ENVIRONMENT";
            default -> "JVMTI_ERROR_" + errorCode;
        };
    }
}
