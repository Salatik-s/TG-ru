package org.telegram.messenger.family;

/** Monotonic deadlines for recovery of the Store transport. */
final class FamilyStoreReconnect {
    private static final int CONNECTING = 1;
    private static final int WAITING_FOR_NETWORK = 2;
    private static final int CONNECTING_TO_PROXY = 4;
    private static final long INITIAL_DELAY_MS = 10_000L;
    private static final long MAX_DELAY_MS = 60_000L;
    private static final long RENEWAL_INTERVAL_MS = 15_000L;
    private long connectingSince = -1;
    private long nextRestart;
    private long nextRenewal;
    private long restartDelay = INITIAL_DELAY_MS;

    synchronized void observe(int state, long now) {
        if (state == CONNECTING || state == WAITING_FOR_NETWORK || state == CONNECTING_TO_PROXY) {
            if (connectingSince == -1) connectingSince = now;
        } else {
            connectingSince = -1;
            nextRestart = 0;
            restartDelay = INITIAL_DELAY_MS;
        }
    }

    synchronized void networkChanged(long now) {
        nextRenewal = 0;
        if (connectingSince != -1) connectingSince = now;
    }

    synchronized boolean isConnecting() {
        return connectingSince != -1;
    }

    synchronized boolean takeRestart(long now) {
        if (connectingSince == -1 || now - connectingSince < INITIAL_DELAY_MS || now < nextRestart) return false;
        nextRestart = now + restartDelay;
        restartDelay = Math.min(MAX_DELAY_MS, restartDelay * 2);
        nextRenewal = Math.max(nextRenewal, now + RENEWAL_INTERVAL_MS);
        return true;
    }

    synchronized boolean takeRenewal(long now) {
        if (now < nextRenewal) return false;
        nextRenewal = now + RENEWAL_INTERVAL_MS;
        return true;
    }
}
