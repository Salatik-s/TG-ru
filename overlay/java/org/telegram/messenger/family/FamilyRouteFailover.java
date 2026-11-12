package org.telegram.messenger.family;

/** Monotonic deadlines for changing between the primary and fallback transports. */
final class FamilyRouteFailover {
    static final long CONNECT_TIMEOUT_MS = 10_000L;
    private static final long RETRY_PRIMARY_MS = 10 * 60_000L;

    private long connectingSince = -1;
    private long retryPrimaryAt = -1;

    boolean observe(boolean connectingOnAvailableNetwork, long now) {
        if (!connectingOnAvailableNetwork || usingFallback()) {
            connectingSince = -1;
        } else if (connectingSince == -1) {
            connectingSince = now;
            return true;
        }
        return false;
    }

    boolean usingFallback() {
        return retryPrimaryAt != -1;
    }

    boolean select(long now, boolean callActive, boolean allowed) {
        if (callActive) return usingFallback();

        if (!allowed) {
            connectingSince = -1;
            retryPrimaryAt = -1;
            return false;
        }

        if (usingFallback()) {
            if (now >= retryPrimaryAt) retryPrimaryAt = -1;
        } else if (connectingSince != -1 && now - connectingSince >= CONNECT_TIMEOUT_MS) {
            retryPrimaryAt = now + RETRY_PRIMARY_MS;
            connectingSince = -1;
        }
        return usingFallback();
    }

    long millisUntilCheck(long now) {
        long deadline = usingFallback() ? retryPrimaryAt
                : connectingSince == -1 ? -1 : connectingSince + CONNECT_TIMEOUT_MS;
        return deadline == -1 ? -1 : Math.max(0, deadline - now);
    }
}
