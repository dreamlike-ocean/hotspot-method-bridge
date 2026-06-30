package io.github.dreamlike.hotspot.vmstruct.jni;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

/**
 * JNI {@code jvalue} union 的 Java 侧表示。
 *
 * <p>一个 {@code jvalue} 固定按 native union 布局写入，主要用于
 * {@code Call<Type>MethodA} / {@code NewObjectA} 这类接收 {@code jvalue*}
 * 的 JNI 入口。
 */
public final class JValue {
    public static final MemoryLayout LAYOUT = MemoryLayout.unionLayout(
            ValueLayout.JAVA_BYTE.withName("z"),
            ValueLayout.JAVA_BYTE.withName("b"),
            ValueLayout.JAVA_CHAR.withName("c"),
            ValueLayout.JAVA_SHORT.withName("s"),
            ValueLayout.JAVA_INT.withName("i"),
            ValueLayout.JAVA_LONG.withName("j"),
            ValueLayout.JAVA_FLOAT.withName("f"),
            ValueLayout.JAVA_DOUBLE.withName("d"),
            ValueLayout.ADDRESS.withName("l")
    );

    private static final VarHandle BOOLEAN = LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("z"));
    private static final VarHandle BYTE = LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("b"));
    private static final VarHandle CHAR = LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("c"));
    private static final VarHandle SHORT = LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("s"));
    private static final VarHandle INT = LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("i"));
    private static final VarHandle LONG = LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("j"));
    private static final VarHandle FLOAT = LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("f"));
    private static final VarHandle DOUBLE = LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("d"));
    private static final VarHandle OBJECT = LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("l"));

    private final Kind kind;
    private final long bits;
    private final MemorySegment object;

    private JValue(Kind kind, long bits, MemorySegment object) {
        this.kind = kind;
        this.bits = bits;
        this.object = object;
    }

    public static JValue ofBoolean(boolean value) {
        return new JValue(Kind.BOOLEAN, value ? 1 : 0, MemorySegment.NULL);
    }

    public static JValue ofByte(byte value) {
        return new JValue(Kind.BYTE, value, MemorySegment.NULL);
    }

    public static JValue ofChar(char value) {
        return new JValue(Kind.CHAR, value, MemorySegment.NULL);
    }

    public static JValue ofShort(short value) {
        return new JValue(Kind.SHORT, value, MemorySegment.NULL);
    }

    public static JValue ofInt(int value) {
        return new JValue(Kind.INT, value, MemorySegment.NULL);
    }

    public static JValue ofLong(long value) {
        return new JValue(Kind.LONG, value, MemorySegment.NULL);
    }

    public static JValue ofFloat(float value) {
        return new JValue(Kind.FLOAT, Float.floatToRawIntBits(value), MemorySegment.NULL);
    }

    public static JValue ofDouble(double value) {
        return new JValue(Kind.DOUBLE, Double.doubleToRawLongBits(value), MemorySegment.NULL);
    }

    public static JValue ofObject(MemorySegment value) {
        return new JValue(Kind.OBJECT, 0, value);
    }

    public static MemorySegment allocate(Arena arena, JValue... values) {
        if (values.length == 0) {
            return MemorySegment.NULL;
        }
        MemorySegment segment = arena.allocate(LAYOUT, values.length);
        for (int i = 0; i < values.length; i++) {
            values[i].writeTo(segment, i);
        }
        return segment;
    }

    public void writeTo(MemorySegment segment, long index) {
        long offset = index * LAYOUT.byteSize();
        switch (kind) {
            case BOOLEAN -> BOOLEAN.set(segment, offset, (byte) bits);
            case BYTE -> BYTE.set(segment, offset, (byte) bits);
            case CHAR -> CHAR.set(segment, offset, (char) bits);
            case SHORT -> SHORT.set(segment, offset, (short) bits);
            case INT -> INT.set(segment, offset, (int) bits);
            case LONG -> LONG.set(segment, offset, bits);
            case FLOAT -> FLOAT.set(segment, offset, Float.intBitsToFloat((int) bits));
            case DOUBLE -> DOUBLE.set(segment, offset, Double.longBitsToDouble(bits));
            case OBJECT -> OBJECT.set(segment, offset, object);
        }
    }

    private enum Kind {
        BOOLEAN,
        BYTE,
        CHAR,
        SHORT,
        INT,
        LONG,
        FLOAT,
        DOUBLE,
        OBJECT
    }
}
