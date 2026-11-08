package org.telegram.messenger.family;

import android.content.Context;

/** Loads the private transport before Telegram's first native initialization. */
public final class FamilyNative {
    private static volatile boolean initialized;

    private FamilyNative() { }

    public static synchronized void initialize(Context context) {
        if (!initialized) {
            // Failure must abort bootstrap. Never continue with the original direct network.
            System.loadLibrary("familytunnel");
            System.loadLibrary("familytgnet");
            FamilyCalls.initialize();
            initialized = true;
        }
        FamilyAccess.init(context);
    }

    public static int start(String gateway) {
        if (!initialized) return -1;
        int result = nativeStart(gateway);
        if (result == 0) {
            try { FamilyCalls.onApprovedTransport(); }
            catch (RuntimeException | LinkageError failure) { nativeStop(); return -1; }
        }
        return result;
    }

    public static void stop() {
        if (initialized) {
            FamilyCalls.revoke();
            nativeStop();
        }
    }

    /** Configured engine with a live lease; this is not proof of remote reachability. */
    public static boolean isReady() {
        return initialized && nativeReady();
    }

    public static int renewLease(int seconds) {
        return initialized ? nativeRenewLease(seconds) : -1;
    }

    public static String lastError() {
        return initialized ? nativeLastError() : "native_unavailable";
    }

    private static native int nativeStart(String gateway);
    private static native void nativeStop();
    private static native boolean nativeReady();
    private static native int nativeRenewLease(int seconds);
    private static native String nativeLastError();
}
