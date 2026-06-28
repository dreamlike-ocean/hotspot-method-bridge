package top.dreamlike.jdk25.openlink;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OpenLinkDemoTest {
    private static long targetCount;
    private static long carrierCount;

    public static void target() {
        targetCount++;
    }

    public static void carrier() {
        carrierCount++;
    }

    public static void interpretedCaller() {
        target();
    }

    public static void compiledCaller() {
        target();
    }

    @Test
    void linksTargetMethodToCarrierNmethod() throws Exception {
        targetCount = 0;
        carrierCount = 0;

        Method target = OpenLinkDemoTest.class.getDeclaredMethod("target");
        Method carrier = OpenLinkDemoTest.class.getDeclaredMethod("carrier");

        HotSpot25Linker.MethodState initialTarget = HotSpot25Linker.state(target);
        assertEquals(0, initialTarget.code());
        assertTrue(HotSpot25Linker.compileNow(carrier, 4));
        HotSpot25Linker.MethodState carrierState = HotSpot25Linker.waitForCode(carrier, 5_000);
        assertNotEquals(0, carrierState.code());

        HotSpot25Linker.Link link = HotSpot25Linker.linkToCarrier(target, carrier);
        HotSpot25Linker.MethodState linkedTarget = HotSpot25Linker.state(target);
        assertEquals(carrierState.code(), link.code());
        assertEquals(carrierState.code(), linkedTarget.code());
        assertEquals(carrierState.compiledEntry(), linkedTarget.compiledEntry());
        assertEquals(carrierState.interpretedEntry(), linkedTarget.interpretedEntry());
        assertEquals(initialTarget.interpreterEntry(), linkedTarget.interpreterEntry());
        assertTrue(HotSpot25Linker.isCurrent(link));
        assertEquals(0, targetCount);
        assertEquals(0, carrierCount);

        HotSpot25Linker.MethodState beforeTargetCompile = HotSpot25Linker.state(target);
        HotSpot25Linker.compileNow(target, 1);
        assertEquals(beforeTargetCompile, HotSpot25Linker.state(target));
        assertTrue(HotSpot25Linker.isCurrent(link));

        interpretedCaller();
        assertEquals(0, targetCount);
        assertEquals(1, carrierCount);

        Method compiledCaller = OpenLinkDemoTest.class.getDeclaredMethod("compiledCaller");
        assertTrue(HotSpot25Linker.compileNow(compiledCaller, 4));
        assertNotEquals(0, HotSpot25Linker.waitForCode(compiledCaller, 5_000).code());

        long carrierBefore = carrierCount;
        for (int i = 0; i < 10_000; i++) {
            compiledCaller();
        }
        assertEquals(0, targetCount);
        assertEquals(carrierBefore + 10_000, carrierCount);
    }
}
