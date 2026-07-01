package io.github.dreamlike.hotspot.classes;

import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeModel;
import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class HotSpotClassesTest {
    private static final String TARGET_NAME = "io/github/dreamlike/hotspot/classes/TransformTarget";

    @Test
    void transformerObservesClassLoadAndRetransform() throws Exception {
        AtomicInteger loadCount = new AtomicInteger();
        AtomicInteger retransformCount = new AtomicInteger();
        AtomicReference<Module> loadModule = new AtomicReference<>();
        AtomicReference<ClassLoader> loadLoader = new AtomicReference<>();
        AtomicReference<Class<?>> retransformedClass = new AtomicReference<>();

        ClassFileTransformer transformer = new ClassFileTransformer() {
            @Override
            public byte[] transform(
                    Module module,
                    ClassLoader loader,
                    String className,
                    Class<?> classBeingRedefined,
                    ProtectionDomain protectionDomain,
                    byte[] classfileBuffer) {
                if (!TARGET_NAME.equals(className)) {
                    return null;
                }
                if (classBeingRedefined == null) {
                    loadCount.incrementAndGet();
                    loadModule.set(module);
                    loadLoader.set(loader);
                    return rewriteValueMethod(classfileBuffer, 1234);
                } else {
                    retransformCount.incrementAndGet();
                    retransformedClass.set(classBeingRedefined);
                    return rewriteValueMethod(classfileBuffer, 2048);
                }
            }
        };

        try (HotSpotClasses.TransformerRegistration ignored = HotSpotClasses.transformerRegister(transformer)) {
            Class<?> target = Class.forName(
                    "io.github.dreamlike.hotspot.classes.TransformTarget",
                    true,
                    HotSpotClassesTest.class.getClassLoader()
            );
            Method value = target.getDeclaredMethod("value");

            assertEquals(1234, value.invoke(null));
            assertEquals(1, loadCount.get());
            assertNotNull(loadModule.get());
            assertSame(loadLoader.get().getUnnamedModule(), loadModule.get());

            HotSpotClasses.retransform(target);

            assertEquals(1, retransformCount.get());
            assertSame(target, retransformedClass.get());
            assertEquals(2048, value.invoke(null));
        }
    }

    private static byte[] rewriteValueMethod(byte[] classfileBuffer, int value) {
        ClassFile classFile = ClassFile.of();
        return classFile.transformClass(
                classFile.parse(classfileBuffer),
                ClassTransform.transformingMethods(
                        method -> method.methodName().equalsString("value")
                                && method.methodType().equalsString("()I"),
                        (builder, element) -> {
                            if (element instanceof CodeModel) {
                                builder.withCode(code -> code.sipush(value).ireturn());
                            } else {
                                builder.with(element);
                            }
                        }
                )
        );
    }
}
