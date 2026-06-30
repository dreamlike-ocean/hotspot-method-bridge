package io.github.dreamlike.hotspot.vmstruct.jni;

import java.lang.foreign.MemorySegment;

/**
 * JNI global reference 的生命周期包装。
 *
 * <p>这里保存的是 JNI handle，不是裸 {@code oop}。GC 会感知 global handle
 * 槽位并在对象移动后修正槽位里的 {@code oop}。
 */
public final class GlobalRef implements AutoCloseable {
    private MemorySegment ref;

    GlobalRef(JNIEnv env, MemorySegment ref) {
        this(ref);
    }

    private GlobalRef(MemorySegment ref) {
        this.ref = ref;
    }

    /**
     * 包装一个已经存在的 JNI global reference 地址。
     *
     * <p>这个方法不会创建新的 JNI global reference，只是接管传入 handle 的释放责任。
     * 调用方必须保证 {@code address} 来自 {@code NewGlobalRef} 或等价路径。
     */
    public static GlobalRef wrap(long address) {
        if (address == 0) {
            throw new IllegalArgumentException("global reference address is NULL");
        }
        return wrap(MemorySegment.ofAddress(address));
    }

    /**
     * 包装一个已经存在的 JNI global reference。
     *
     * <p>这个方法不会创建新的 JNI global reference，只是接管传入 handle 的释放责任。
     */
    public static GlobalRef wrap(MemorySegment ref) {
        if (ref == MemorySegment.NULL || ref.address() == 0) {
            throw new IllegalArgumentException("global reference is NULL");
        }
        return new GlobalRef(ref);
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
            JNIEnv.current().deleteGlobalRef(current);
        }
    }
}
