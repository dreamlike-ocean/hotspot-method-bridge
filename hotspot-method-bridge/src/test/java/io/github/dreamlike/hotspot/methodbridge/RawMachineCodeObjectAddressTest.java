package io.github.dreamlike.hotspot.methodbridge;

import io.github.dreamlike.hotspot.vmstruct.NarrowOopEncoding;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs({OS.MAC, OS.LINUX})
@EnabledIf("isSupportedPlatform")
final class RawMachineCodeObjectAddressTest {
    private static final Unsafe UNSAFE = unsafe();
    private static final long VALUE_OFFSET = objectFieldOffset();
    private static final int REFERENCE_SIZE = UNSAFE.arrayIndexScale(Object[].class);
    private static final NarrowOopEncoding NARROW_OOP_ENCODING = NarrowOopEncoding.load();
    // HotSpot aarch64 的 Java ABI 是 shifted C ABI，Java 第一个参数 j_rarg0 对应 x1。
    private static final byte[] RETURN_X1 = {(byte) 0xe0, 0x03, 0x01, (byte) 0xaa, (byte) 0xc0, 0x03, 0x5f, (byte) 0xd6};
    // Linux x86_64 下 j_rarg0 = c_rarg1 = rsi；long 返回值放在 rax。
    private static final byte[] RETURN_RSI = {0x48, (byte) 0x89, (byte) 0xf0, (byte) 0xc3};
    private static HotSpotMethodBridge.RawCodeLink link;

    public static long getObjectAddress(Object a) {
        return 0;
    }

    @BeforeAll
    static void bindRawCode() throws Exception {
        Method target = RawMachineCodeObjectAddressTest.class.getDeclaredMethod("getObjectAddress", Object.class);
        link = HotSpotMethodBridge.linkToMachineCode(target, rawCode());
        assertTrue(HotSpotMethodBridge.isCurrent(link));
        assertEquals(0, HotSpotMethodBridge.state(target).code());
    }

    @Test
    @DisplayName("raw 机器码返回 Object oop")
    void objectAddressMatchesUnsafe() {
        assertTrue(HotSpotMethodBridge.isCurrent(link));
        Object value = new Object();
        long actual = getObjectAddress(value);
        assertNotEquals(0, actual);
        assertEquals(unsafeObjectAddress(value), actual);
    }

    private static byte[] rawCode() {
        if (isAarch64()) {
            return RETURN_X1;
        }
        if (isLinuxX64()) {
            return RETURN_RSI;
        }
        throw new UnsupportedOperationException("unsupported OS/arch: "
                + System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
    }

    private static long unsafeObjectAddress(Object value) {
        ReferenceSlot holder = new ReferenceSlot(value);
        long raw = switch (REFERENCE_SIZE) {
            case Integer.BYTES -> Integer.toUnsignedLong(UNSAFE.getInt(holder, VALUE_OFFSET));
            case Long.BYTES -> UNSAFE.getLong(holder, VALUE_OFFSET);
            default -> throw new IllegalStateException("unsupported oop slot size: " + REFERENCE_SIZE);
        };
        return NARROW_OOP_ENCODING.decodeReference(raw, REFERENCE_SIZE);
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

    private static long objectFieldOffset() {
        try {
            return UNSAFE.objectFieldOffset(ReferenceSlot.class.getDeclaredField("value"));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static boolean isAarch64() {
        String arch = System.getProperty("os.arch");
        return arch.equals("aarch64") || arch.equals("arm64");
    }

    private static boolean isSupportedPlatform() {
        return isAarch64() || isLinuxX64();
    }

    private static boolean isLinuxX64() {
        String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
        String arch = System.getProperty("os.arch");
        return os.contains("linux") && (arch.equals("amd64") || arch.equals("x86_64"));
    }

    private static final class ReferenceSlot {
        private final Object value;

        private ReferenceSlot(Object value) {
            this.value = value;
        }
    }
}
