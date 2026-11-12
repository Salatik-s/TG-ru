package org.telegram.messenger.family;

import android.app.Activity;
import android.app.AlertDialog;
import java.lang.ref.WeakReference;
import org.telegram.messenger.R;

final class FamilyRouteConsent {
    private static WeakReference<AlertDialog> prompt = new WeakReference<>(null);

    private FamilyRouteConsent() { }

    static void maybeAsk(Activity activity) {
        if (FamilyAccess.hasRouteFailoverChoice() || activity.isFinishing() || activity.isDestroyed()) return;
        AlertDialog open = prompt.get();
        if (open != null && open.isShowing()) return;

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.fg_route_title)
                .setMessage(R.string.fg_route_consent)
                .setPositiveButton(R.string.fg_route_allow, (d, which) -> FamilyAccess.setRouteFailoverEnabled(true))
                .setNegativeButton(R.string.fg_route_decline, (d, which) -> FamilyAccess.setRouteFailoverEnabled(false))
                .setOnCancelListener(d -> FamilyAccess.setRouteFailoverEnabled(false))
                .create();
        prompt = new WeakReference<>(dialog);
        dialog.show();
    }
}
