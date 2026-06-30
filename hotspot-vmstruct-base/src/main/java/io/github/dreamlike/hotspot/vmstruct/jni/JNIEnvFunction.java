package io.github.dreamlike.hotspot.vmstruct.jni;

/**
 * JNI 函数表 {@code JNINativeInterface_} 的槽位下标。
 *
 * <p>{@code JNIEnv*} 本身指向一个结构体，结构体第一个字段是函数表指针；
 * 取到函数表后按这些下标乘以机器指针宽度，就能拿到对应 JNI 函数入口。
 */
public enum JNIEnvFunction {
    GET_VERSION(4),
    FIND_CLASS(6),
    FROM_REFLECTED_METHOD(7),
    FROM_REFLECTED_FIELD(8),
    EXCEPTION_OCCURRED(15),
    EXCEPTION_DESCRIBE(16),
    EXCEPTION_CLEAR(17),
    NEW_GLOBAL_REF(21),
    DELETE_GLOBAL_REF(22),
    DELETE_LOCAL_REF(23),
    IS_SAME_OBJECT(24),
    NEW_LOCAL_REF(25),
    GET_OBJECT_CLASS(31),
    GET_METHOD_ID(33),
    CALL_OBJECT_METHOD_A(36),
    CALL_VOID_METHOD_A(63),
    GET_STATIC_METHOD_ID(113),
    CALL_STATIC_OBJECT_METHOD_A(116),
    CALL_STATIC_VOID_METHOD_A(143),
    NEW_STRING_UTF(167),
    GET_ARRAY_LENGTH(171),
    NEW_BYTE_ARRAY(176),
    GET_BYTE_ARRAY_REGION(200),
    SET_BYTE_ARRAY_REGION(208),
    GET_JAVA_VM(219),
    EXCEPTION_CHECK(228),
    GET_MODULE(233),
    IS_VIRTUAL_THREAD(234);

    private final int index;

    JNIEnvFunction(int index) {
        this.index = index;
    }

    public int index() {
        return index;
    }
}
