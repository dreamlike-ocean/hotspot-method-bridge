package io.github.dreamlike.hotspot.vmstruct.jni;

import java.lang.foreign.MemorySegment;

/**
 * JNI global reference 的生命周期包装。
 *
 * <p>这里保存的是 JNI handle，不是裸 {@code oop}。GC 会感知 global handle
 * 槽位并在对象移动后修正槽位里的 {@code oop}。
 */
public final class GlobalRef implements AutoCloseable {
    private final JNIEnv env;
    private MemorySegment ref;

    GlobalRef(JNIEnv env, MemorySegment ref) {
        this.env = env;
        this.ref = ref;
    }

    public MemorySegment ref() {
        if (ref == MemorySegment.NULL) {
            throw new IllegalStateException("global reference already closed");
        }
        return ref;
    }

    public long address() {
        return ref().address();
    }

    @Override
    public void close() {
        MemorySegment current = ref;
        if (current != MemorySegment.NULL) {
            ref = MemorySegment.NULL;
            env.deleteGlobalRef(current);
        }
    }
}
