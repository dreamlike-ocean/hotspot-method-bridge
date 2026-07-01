package io.github.dreamlike.hotspot.methodbridge;

import io.github.dreamlike.hotspot.vmstruct.jni.GlobalRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HotSpotGlobalRefsTest {

    @Test
    @DisplayName("Java 对象可以通过 native/upcall 包装成 JNI global reference")
    void javaObjectCanBeWrappedAsGlobalRef() {
        Object object = new Object();

        try (GlobalRef ref = HotSpotGlobalRefs.newGlobalRef(object)) {
            assertNotEquals(0, ref.address());
            Object res = HotSpotGlobalRefs.fromJObject(ref.address());
            assertSame(object, res);
        }
    }
}
