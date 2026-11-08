package org.telegram.messenger.family;

/** Native call transport bootstrap. Any load/install failure must abort startup. */
public final class FamilyCalls {
    private static boolean loaded;

    private FamilyCalls() {}

    public static synchronized void initialize() {
        if (loaded) return;
        System.loadLibrary("familycalls");
        if (!nativeInstalled()) throw new UnsatisfiedLinkError("Private call transport unavailable");
        loaded = true;
    }

    /** Call only after FGStart succeeded using a freshly authorized profile. */
    public static synchronized void onApprovedTransport() {
        if (!loaded || nativeResume() != 0) throw new IllegalStateException("Private call transport unavailable");
    }

    /** Call before FGStop/revocation, and terminate active VoIP sessions in Java. */
    public static synchronized void revoke() {
        if (loaded) nativeStop();
    }

    private static native boolean nativeInstalled();
    private static native int nativeResume();
    private static native void nativeStop();
}
