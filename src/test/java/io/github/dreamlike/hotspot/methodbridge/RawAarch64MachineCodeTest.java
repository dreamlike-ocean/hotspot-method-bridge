package io.github.dreamlike.hotspot.methodbridge;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs({OS.MAC, OS.LINUX})
@EnabledIfSystemProperty(named = "os.arch", matches = "aarch64|arm64")
final class RawAarch64MachineCodeTest {
    private static final Unsafe UNSAFE = unsafe();
    // HotSpot aarch64 的 Java ABI 是 shifted C ABI，Java 第一个参数 j_rarg0 对应 x1。
    private static final byte[] RETURN_X1 = {(byte) 0xe0, 0x03, 0x01, (byte) 0xaa, (byte) 0xc0, 0x03, 0x5f, (byte) 0xd6};
    private static HotSpotMethodBridge.RawCodeLink link;

    public static long getObjectAddress(Object a) {
        return 0;
    }

    @BeforeAll
    static void bindRawCode() throws Exception {
        Method target = RawAarch64MachineCodeTest.class.getDeclaredMethod("getObjectAddress", Object.class);
        link = HotSpotMethodBridge.linkToMachineCode(target, RETURN_X1);
        assertTrue(HotSpotMethodBridge.isCurrent(link));
        assertEquals(0, HotSpotMethodBridge.state(target).code());
    }

    @Test
    @EnabledOnOs(OS.MAC)
    @DisplayName("macOS aarch64: raw 机器码返回 Object oop")
    void macAarch64ObjectAddressMatchesUnsafe() {
        objectAddressMatchesUnsafe();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    @DisplayName("Linux aarch64: raw 机器码返回 Object oop")
    void linuxAarch64ObjectAddressMatchesUnsafe() {
        objectAddressMatchesUnsafe();
    }

    private static void objectAddressMatchesUnsafe() {
        Object value = new Object();
        long actual = getObjectAddress(value);
        long expected = unsafeObjectAddress(value);
        assertNotEquals(0, actual);
        assertEquals(expected, actual);
    }

    private static long unsafeObjectAddress(Object value) {
        Object[] holder = {value};
        long base = UNSAFE.arrayBaseOffset(Object[].class);
        int scale = UNSAFE.arrayIndexScale(Object[].class);
        long raw = switch (scale) {
            case Integer.BYTES -> Integer.toUnsignedLong(UNSAFE.getInt(holder, base));
            case Long.BYTES -> UNSAFE.getLong(holder, base);
            default -> throw new IllegalStateException("unsupported oop slot size: " + scale);
        };
        return HotSpotMethodBridge.decodeObjectReference(raw, scale);
    }

    private static Unsafe unsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
