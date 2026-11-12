package org.telegram.messenger.family;

public final class FamilyRouteFailoverTest {
    private static void require(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        FamilyRouteFailover route = new FamilyRouteFailover();
        require(route.observe(true, 0), "start watching the primary connection");
        require(!route.observe(true, 8_000), "repeated connecting events keep the original deadline");
        require(route.millisUntilCheck(8_000) == 2_000, "remaining delay before failover");
        require(!route.select(9_999, false, true), "primary stays active until ten seconds");
        require(route.select(10_000, false, true), "switch to fallback at ten seconds");
        require(!route.observe(true, 11_000), "fallback does not start another primary timer");
        require(route.millisUntilCheck(609_000) == 1_000, "return check is scheduled at ten minutes");
        require(route.select(609_999, false, true), "stay on fallback before its deadline");
        require(route.select(610_000, true, true), "an active call defers returning to primary");
        require(!route.select(610_001, false, true), "return to primary after the call");

        require(route.observe(true, 620_000), "a failed primary retry starts a fresh timeout");
        route.observe(false, 625_000);
        require(!route.select(630_000, false, true), "recovery or offline resets the timeout");
        require(route.observe(true, 640_000), "resume watching after network recovery");
        require(!route.select(650_000, true, true), "an active call defers failover too");
        require(route.select(650_001, false, true), "fail over as soon as a later check can switch");
        require(route.select(651_000, true, false), "disabling the feature preserves an ongoing call");
        require(!route.select(652_000, false, false), "disabling returns to primary without the ten-minute wait");
        route.observe(true, 660_000);
        require(!route.select(680_000, false, false), "fallback cannot start without consent");
        System.out.println("PASS route deadlines, consent, reset and call deferral");
    }
}
