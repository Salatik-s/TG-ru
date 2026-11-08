import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from prepare_repack import patch_activity_text, patch_application, merge_resources, patch_settings, patch_manifest, patch_private_calls, remove_crashlytics_enable, disable_crash_reporting_methods, force_family_push_provider, A


class RepackTests(unittest.TestCase):
    def test_activity_wrapper_preserves_original_registers_and_instructions(self):
        source = '''.class public Lorg/telegram/ui/LaunchActivity;
.super Lorg/telegram/ui/BasePermissionsActivity;
.method protected onCreate(Landroid/os/Bundle;)V
    .registers 19
    invoke-super {p0, p1}, Lorg/telegram/ui/BasePermissionsActivity;->onCreate(Landroid/os/Bundle;)V
    return-void
.end method
.method protected onResume()V
    .locals 6
    invoke-super {p0}, Lorg/telegram/ui/BasePermissionsActivity;->onResume()V
    return-void
.end method
.method private handleIntent(Landroid/content/Intent;ZZZ)Z
    .locals 17
    const/4 v0, 0x1
    return v0
.end method
'''
        result, methods = patch_activity_text(source, 'org.telegram.ui.LaunchActivity')
        self.assertIn('.registers 19', result)
        self.assertIn('.locals 17', result)
        self.assertEqual(result.count('.method protected onCreate('), 1)
        self.assertIn('familyBinaryInitialized:Z', result)
        self.assertIn('invoke-direct/range {p0 .. p4}', result)
        self.assertEqual(len(methods), 3)
        self.assertLess(result.index('.class'), result.index('.method'))
        with self.assertRaises(ValueError):
            patch_activity_text(result, 'org.telegram.ui.LaunchActivity')

    def test_inherited_oncreate_is_gated_without_reordering_class_header(self):
        source = '.class public Ltest/Activity;\n.super Landroid/app/Activity;\n'
        result, methods = patch_activity_text(source, 'test.Activity')
        self.assertTrue(result.startswith('.class'))
        self.assertIn('onCreate(Landroid/os/Bundle;)V', methods)
        self.assertIn('invoke-super/range {p0 .. p1}', result)

    def test_deferred_family_link_uses_public_passcode_handler_virtual_dispatch(self):
        descriptor = '(Landroid/content/Intent;ZZZLorg/telegram/messenger/browser/Browser$Progress;ZZ)Z'
        source = '.class public Lorg/telegram/ui/LaunchActivity;\n.super Landroid/app/Activity;\n'
        for head in ('private handleIntent', 'public final handleIntentInternal'):
            source += '.method ' + head + descriptor + '\n    .locals 1\n    const/4 v0, 0x0\n    return v0\n.end method\n'
        result, _ = patch_activity_text(source, 'org.telegram.ui.LaunchActivity')
        self.assertIn('invoke-virtual/range {p0 .. p7}, Lorg/telegram/ui/LaunchActivity;->handleIntentInternal' + descriptor, result)
        self.assertNotIn('invoke-direct/range {p0 .. p7}, Lorg/telegram/ui/LaunchActivity;->handleIntentInternal', result)

    def test_application_requires_both_native_bootstrap_points(self):
        call = '    invoke-static {v0}, Lorg/telegram/messenger/NativeLoader;->initNativeLibs(Landroid/content/Context;)V\n'
        source = '.class public Lorg/telegram/messenger/ApplicationLoader;\n.super Landroid/app/Application;\n'
        source += '.method public onCreate()V\n    .locals 1\n    sput-object p0, Lorg/telegram/messenger/ApplicationLoader;->applicationContext:Landroid/content/Context;\n' + call + '    return-void\n.end method\n'
        source += '.method private static initPushServices()V\n    .locals 0\n    return-void\n.end method\n'
        source += '.method public static postInitApplication()V\n    .locals 1\n' + call + '    return-void\n.end method\n'
        result = patch_application(source)
        self.assertEqual(result.count('FamilyNative;->initialize'), 2)
        self.assertEqual(result.count('FamilyTelegramPreset;->apply'), 3)
        self.assertEqual(result.count('.method public static postInitApplication()V'), 1)
        self.assertIn('if-eqz v0, :family_done', result)
        with self.assertRaises(ValueError):
            patch_application(source.replace(call, '', 1))

    def test_new_resources_keep_original_ids_and_allocate_after_maximum(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            decoded = root / 'decoded'
            overlay = root / 'overlay'
            (decoded / 'res/values').mkdir(parents=True)
            (overlay / 'res/values').mkdir(parents=True)
            public = decoded / 'res/values/public.xml'
            public.write_text('<resources><public type="string" name="existing" id="0x7f120010"/></resources>')
            (overlay / 'res/values/fg.xml').write_text('<resources><string name="fg_one">One</string><string name="fg_two">Two</string></resources>')
            result = merge_resources(decoded, overlay, root / 'generated')
            self.assertEqual(result['string/fg_one'], '0x7f120011')
            self.assertEqual(result['string/fg_two'], '0x7f120012')
            self.assertEqual(ET.parse(public).getroot()[0].attrib['id'], '0x7f120010')
            self.assertIn('0x7f120011', (root / 'generated/R.java').read_text())

    def test_settings_uses_stock_callbacks_and_unique_row_bridge(self):
        source = '.class public Lorg/telegram/ui/SettingsActivity;\n.super Ljava/lang/Object;\n'
        for signature in ('fillItems(Ljava/util/ArrayList;Lorg/telegram/ui/Components/UniversalAdapter;)V', 'onClick(Lorg/telegram/ui/Components/UItem;Landroid/view/View;IFF)V'):
            source += '.method private ' + signature + '\n    .locals 17\n    return-void\n.end method\n'
        patched = patch_settings(source)
        self.assertIn('FamilyBinarySettings;->addRow', patched)
        self.assertIn('FamilyBinarySettings;->onClick', patched)
        self.assertEqual(patched.count('.locals 17'), 2)
        with self.assertRaises(ValueError):
            patch_settings('')

    def test_private_call_p2p_disabled_without_changing_relay_true_registers(self):
        source = '    iget-boolean v7, v2, Lorg/telegram/tgnet/tl/TL_phone$PhoneCall;->p2p_allowed:Z\n'
        for register in ('v1', 'p1', 'p3'):
            source += '    const/4 v3, 0x1\n'
            source += f'    iput-boolean v3, {register}, Lorg/telegram/tgnet/tl/TL_phone$PhoneCallProtocol;->udp_reflector:Z\n'
            source += f'    iput-boolean v3, {register}, Lorg/telegram/tgnet/tl/TL_phone$PhoneCallProtocol;->udp_p2p:Z\n'
        patched = patch_private_calls(source)
        self.assertIn('const/16 v7, 0x0', patched)
        self.assertNotIn('->udp_p2p:Z', patched)
        self.assertEqual(patched.count('->udp_reflector:Z'), 3)
        self.assertEqual(patched.count('const/4 v3, 0x1'), 3)
        with self.assertRaises(ValueError):
            patch_private_calls(patched)

    def test_telemetry_removal_requires_exact_matching_register_chain(self):
        source = '''invoke-static {}, Lcom/google/firebase/crashlytics/FirebaseCrashlytics;->getInstance()Lcom/google/firebase/crashlytics/FirebaseCrashlytics;
    move-result-object p0
    .line 200
    invoke-static {}, Ltw/nekomimi/nekogram/utils/AndroidUtil;->shouldEnableCrashlytics()Z
    move-result p1
    .line 199
    invoke-virtual {p0, p1}, Lcom/google/firebase/crashlytics/FirebaseCrashlytics;->setCrashlyticsCollectionEnabled(Z)V
    return-void
'''
        patched = remove_crashlytics_enable(source)
        self.assertNotIn('->getInstance', patched)
        self.assertIn('return-void', patched)
        with self.assertRaises(ValueError):
            remove_crashlytics_enable(source.replace('invoke-virtual {p0, p1}', 'invoke-virtual {p2, p1}'))
        methods = ''
        for name in ('putCrashlyticsKeys(Ljava/lang/Throwable;)V', 'reportOutOfMemory()V', 'record(Ljava/lang/Thread;Ljava/lang/Throwable;)V'):
            methods += '.method public static ' + name + '\n    .locals 2\n    const/4 v0, 0x1\n    return-void\n.end method\n'
        patched = disable_crash_reporting_methods(methods)
        self.assertEqual(patched.count('.locals 0'), 2)
        self.assertEqual(patched.count('const/4 v0, 0x1'), 1)

    def test_push_factory_uses_verified_dummy_constructor_and_ignores_old_preferences(self):
        source = '''.method public static getProvider()Lorg/telegram/messenger/PushListenerController$IPushListenerServiceProvider;
    .locals 2
    new-instance v0, Lorg/telegram/messenger/GooglePushListenerServiceProvider;
    invoke-direct {v0, v1}, Lorg/telegram/messenger/PushListenerController$DummyPushProvider;-><init>(Lorg/telegram/messenger/PushListenerController-IA;)V
    return-object v0
.end method
'''
        patched = force_family_push_provider(source)
        self.assertNotIn('GooglePushListenerServiceProvider', patched)
        self.assertIn('instance-of v1, v0, Lorg/telegram/messenger/PushListenerController$DummyPushProvider;', patched)
        self.assertIn('const/4 v1, 0x0', patched)
        with self.assertRaises(ValueError):
            force_family_push_provider(source.replace('PushListenerController-IA;', 'Unexpected;'))

    def test_manifest_separates_identity_and_preserves_component_classes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'AndroidManifest.xml').write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.exteraless.app"><uses-sdk android:minSdkVersion="21"/><application android:name="org.telegram.messenger.ApplicationLoader"><activity android:name="org.telegram.ui.LaunchActivity" android:exported="true"/><provider android:name="com.google.firebase.provider.FirebaseInitProvider" android:authorities="com.exteraless.app.firebaseinitprovider"/></application></manifest>''')
            rows = patch_manifest(root)
            manifest = ET.parse(root / 'AndroidManifest.xml').getroot()
            app = manifest.find('application')
            self.assertEqual(manifest.get('package'), 'com.salatik.familygram')
            self.assertEqual(rows[0]['name'], 'org.telegram.ui.LaunchActivity')
            self.assertEqual(app.get(A + 'usesCleartextTraffic'), 'false')
            self.assertEqual(app.get(A + 'label'), 'FamilyGram')
            provider = app.find('provider')
            self.assertNotEqual(provider.get(A + 'enabled'), 'false')
            self.assertEqual(provider.get(A + 'authorities'), 'com.salatik.familygram.firebaseinitprovider')


if __name__ == '__main__':
    unittest.main()
