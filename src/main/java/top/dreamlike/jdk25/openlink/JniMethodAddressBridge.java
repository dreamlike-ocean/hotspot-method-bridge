package top.dreamlike.jdk25.openlink;

import java.lang.reflect.Method;

public final class JniMethodAddressBridge {
    private JniMethodAddressBridge() {
    }

    public static native long resolve(Method method);
}
