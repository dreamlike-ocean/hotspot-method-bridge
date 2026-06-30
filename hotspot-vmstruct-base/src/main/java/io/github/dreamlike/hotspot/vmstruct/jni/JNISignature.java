package io.github.dreamlike.hotspot.vmstruct.jni;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Collectors;

public final class JNISignature {

    private JNISignature() {
    }

    public static String of(Class<?> type) {
        if (type == void.class) {
            return "V";
        }
        if (type == boolean.class) {
            return "Z";
        }
        if (type == byte.class) {
            return "B";
        }
        if (type == char.class) {
            return "C";
        }
        if (type == short.class) {
            return "S";
        }
        if (type == int.class) {
            return "I";
        }
        if (type == long.class) {
            return "J";
        }
        if (type == float.class) {
            return "F";
        }
        if (type == double.class) {
            return "D";
        }
        if (type.isArray()) {
            return type.getName().replace('.', '/');
        }
        return "L" + internalName(type) + ";";
    }

    public static String of(Method method) {
        return "(" + parameters(method.getParameterTypes()) + ")" + of(method.getReturnType());
    }

    public static String of(Constructor<?> constructor) {
        return "(" + parameters(constructor.getParameterTypes()) + ")V";
    }

    public static String internalName(Class<?> type) {
        if (type.isPrimitive()) {
            throw new IllegalArgumentException("primitive class has no JNI internal name: " + type);
        }
        return type.getName().replace('.', '/');
    }

    private static String parameters(Class<?>[] parameterTypes) {
        return Arrays.stream(parameterTypes)
                .map(JNISignature::of)
                .collect(Collectors.joining());
    }
}
