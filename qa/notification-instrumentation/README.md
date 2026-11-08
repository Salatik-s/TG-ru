# Own foreground notification instrumentation

Separate test-only package `com.salatik.familygram.notificationdiag`, targeting
`com.salatik.familygram`. Requires the same existing signing key as the installed
app. Sources only were prepared; no compilation or device run is claimed here.

The coordinator may build with `python qa/notification-instrumentation/build.py --sign`.
The script builds/signs only this small diagnostic APK and never runs adb. Output:
`qa/notification-instrumentation/build/Notification-Diagnostic.apk`.

The coordinator controls any subsequent install/run on the authorized phone:

```
adb -s <authorized-device> install -r -t <Notification-Diagnostic.apk>
adb -s <authorized-device> shell am instrument -w -r -e expected_version 10012 com.salatik.familygram.notificationdiag/com.salatik.familygram.notificationdiag.NotificationDiagnostic
```

Instrumentation can restart the target process. It launches the normal installed
app, retaining its account, Keystore, preferences and existing approved lease flow.
It makes no enrollment/owner call, sends no Telegram messages and does not change
permissions, notification channels, network settings or security controls.

Wait budget: at most 30 seconds for the normal app/lease/service path, with bounded
2-second main-thread inspections and 500 ms polling. The service's own 2-second
refresh is allowed to catch up. Two matching samples are required.

`NotificationManager.getActiveNotifications()` returns the target app's records.
The inspector checks only each record's ID and package until **both** match
`0x464701` and `com.salatik.familygram`. It never reads any other notification's
extras, text, sender, tag or key. It does not use NotificationListener access or
dump SystemUI. No screenshot or RemoteViews rendering is collected.

The one matching record is checked for title TG-ru, body matching the actual
private `FamilyPushService.telegramConnected()` result, foreground/ongoing flags,
service category, showWhen=false, localOnly, onlyAlertOnce, preserved channel ID
and channel name TG-ru. Existing user-set channel importance is reported as a
boolean, never changed and not treated as a failure. Visual collapse is OS policy.

Output contains only fixed result tokens, booleans and elapsed milliseconds.
`OWN_NOTIFICATION_PASS` proves the observed current state; it does not prove a
network-disconnect transition or delivery of real chat notifications. A missing
record may reflect disabled notifications or missing approval and does not justify
resetting the user's settings. The test never toggles connectivity to force a state.
