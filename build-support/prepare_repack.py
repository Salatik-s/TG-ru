"""Pinned binary contingency. Writes only below familygram/build-support.

This is not a substitute for a source build or Android runtime verification.
"""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent
EXPECTED = '3855d366b9fa7b49e9219ac26a698c4307e0d4c205dae9b7188ef351d0a3a2c5'
ORIGINAL_PACKAGE = 'com.exteraless.app'
FAMILY_PACKAGE = 'com.salatik.familygram'
A = '{http://schemas.android.com/apk/res/android}'
ET.register_namespace('android', A[1:-1])


def within_work(path):
    path = Path(path).resolve()
    if not path.is_relative_to(ROOT) or path == ROOT:
        raise ValueError('Work must be a child of familygram/build-support')
    return path


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def resource_names(res):
    names = set()
    for folder in Path(res).iterdir():
        if not folder.is_dir():
            continue
        kind = folder.name.split('-')[0]
        for file in folder.iterdir():
            if kind == 'values' and file.suffix == '.xml':
                for node in ET.parse(file).getroot():
                    if node.tag in ('string', 'color', 'style', 'dimen', 'bool', 'integer'):
                        names.add((node.tag, node.attrib['name']))
                    elif node.tag == 'item':
                        names.add((node.attrib['type'], node.attrib['name']))
                    else:
                        raise ValueError(f'Unsupported overlay resource element: {node.tag}')
            elif kind != 'values':
                names.add((kind, file.stem))
    return sorted(names)


def merge_resources(decoded, overlay, generated):
    public_path = decoded / 'res/values/public.xml'
    tree = ET.parse(public_path)
    existing = {(n.attrib['type'], n.attrib['name']): int(n.attrib['id'], 16) for n in tree.getroot()}
    additions = resource_names(overlay / 'res')
    if any(key in existing for key in additions):
        raise ValueError('Overlay resource collides with an existing resource; refusing to overwrite')
    allocated = {}
    for kind, name in additions:
        ids = [value for (typ, _), value in existing.items() if typ == kind]
        if not ids:
            raise ValueError(f'No existing resource type {kind}; explicit type allocation is required')
        value = max(ids) + 1
        if value & 0xffff == 0:
            raise ValueError(f'Resource type {kind} has exhausted its entry IDs')
        existing[kind, name] = value
        allocated[kind, name] = value
        ET.SubElement(tree.getroot(), 'public', {'type': kind, 'name': name, 'id': f'0x{value:08x}'})
    for file in (overlay / 'res').rglob('*'):
        if file.is_file():
            target = decoded / 'res' / file.relative_to(overlay / 'res')
            if target.exists():
                raise ValueError(f'Resource file already exists: {target.name}')
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(file, target)
    tree.write(public_path, encoding='utf-8', xml_declaration=True)
    lines = ['package org.telegram.messenger;', 'public final class R {']
    for kind in sorted({k[0] for k in allocated}):
        lines.append(f'public static final class {kind} {{')
        for (typ, name), value in sorted(allocated.items()):
            if typ == kind:
                java_name = name.replace('.', '_')
                if not re.fullmatch(r'[a-zA-Z_][a-zA-Z_0-9]*', java_name):
                    raise ValueError('Unsupported Java resource identifier')
                lines.append(f'public static final int {java_name} = 0x{value:08x};')
        lines.append('}')
    lines.append('}')
    generated.mkdir(parents=True, exist_ok=True)
    (generated / 'R.java').write_text('\n'.join(lines), encoding='utf-8')
    return {f'{typ}/{name}': f'0x{value:08x}' for (typ, name), value in allocated.items()}


def full_name(name, package):
    return package + name if name.startswith('.') else package + '.' + name if '.' not in name else name


def patch_manifest(decoded):
    path = decoded / 'AndroidManifest.xml'
    tree = ET.parse(path)
    manifest = tree.getroot()
    application = manifest.find('application')
    if application is None:
        raise ValueError('No application')
    package = manifest.attrib['package']
    if package != ORIGINAL_PACKAGE:
        raise ValueError(f'Unexpected upstream package: {package}')
    # Resolve relative component class names before changing the application ID.
    for node in application.iter():
        if node.tag in ('application', 'activity', 'activity-alias', 'service', 'receiver', 'provider'):
            for attr in ('name', 'targetActivity', 'backupAgent', 'manageSpaceActivity'):
                key = A + attr
                if key in node.attrib:
                    node.set(key, full_name(node.attrib[key], package))
    activities = []
    for node in application.findall('activity'):
        name = full_name(node.attrib[A + 'name'], package)
        activities.append({'name': name, 'exported': node.attrib.get(A + 'exported', 'implicit')})
    for suffix in ('FamilyGateActivity', 'FamilySettingsActivity'):
        name = 'org.telegram.messenger.family.' + suffix
        if any(row['name'] == name for row in activities):
            raise ValueError('Gate is already in the manifest')
        ET.SubElement(application, 'activity', {A + 'name': name, A + 'exported': 'false',
            A + 'theme': '@style/FamilyGramTheme', A + 'windowSoftInputMode': 'adjustResize'})
    ET.SubElement(application, 'service', {
        A + 'name': 'org.telegram.messenger.family.FamilyPushService',
        A + 'exported': 'false', A + 'foregroundServiceType': 'remoteMessaging',
    })
    permissions = {node.get(A + 'name') for node in manifest.findall('uses-permission')}
    for permission in ('android.permission.FOREGROUND_SERVICE', 'android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING', 'android.permission.POST_NOTIFICATIONS'):
        if permission not in permissions:
            ET.SubElement(manifest, 'uses-permission', {A + 'name': permission})
    # A newly signed private build must not restore another installation's app data.
    application.set(A + 'allowBackup', 'false')
    application.set(A + 'restoreAnyVersion', 'false')
    application.set(A + 'usesCleartextTraffic', 'false')
    application.set(A + 'networkSecurityConfig', '@xml/fg_network_security')
    application.set(A + 'label', 'FamilyGram')
    for alias in application.findall('activity-alias'):
        alias.set(A + 'label', 'FamilyGram')
    for key, value in {
        'firebase_analytics_collection_deactivated': 'true',
        'firebase_analytics_collection_enabled': 'false',
        'firebase_crashlytics_collection_enabled': 'false',
        'google_analytics_adid_collection_enabled': 'false',
        'firebase_messaging_auto_init_enabled': 'false',
        'firebase_data_collection_default_enabled': 'false',
    }.items():
        nodes = [n for n in application.findall('meta-data') if n.get(A + 'name') == key]
        node = nodes[0] if nodes else ET.SubElement(application, 'meta-data', {A + 'name': key})
        node.set(A + 'value', value)
    # Disable dedicated telemetry providers, but preserve FirebaseInitProvider/FCM.
    for node in application.findall('provider'):
        name = node.get(A + 'name', '').lower()
        if 'crashlytics' in name or 'analytics' in name:
            node.set(A + 'enabled', 'false')
    for node in application.iter('meta-data'):
        if 'CrashlyticsRegistrar' in node.get(A + 'name', ''):
            node.set(A + 'value', 'disabled')
    manifest.set('package', FAMILY_PACKAGE)
    uses_sdk = manifest.find('uses-sdk')
    if uses_sdk is not None and int(uses_sdk.attrib.get(A + 'minSdkVersion', '1')) < 23:
        uses_sdk.set(A + 'minSdkVersion', '23')
    for node in manifest.iter():
        for key, value in list(node.attrib.items()):
            if ORIGINAL_PACKAGE in value and key != A + 'name':
                node.set(key, value.replace(ORIGINAL_PACKAGE, FAMILY_PACKAGE))
            elif key == A + 'name' and node.tag in ('permission', 'uses-permission', 'action'):
                node.set(key, value.replace(ORIGINAL_PACKAGE, FAMILY_PACKAGE))
    tree.write(path, encoding='utf-8', xml_declaration=True)
    return activities


def find_smali(decoded, class_name):
    suffix = Path(*class_name.split('.')).with_suffix('.smali')
    paths = [folder / suffix for folder in decoded.glob('smali*') if (folder / suffix).is_file()]
    if len(paths) != 1:
        raise ValueError(f'Expected one smali definition for {class_name}, found {len(paths)}')
    return paths[0]


METHOD = re.compile(r'^\.method (?P<head>[^\n]+)\n(?P<body>.*?)^\.end method\s*$', re.M | re.S)
LIFECYCLE = {
    'onStart()V', 'onResume()V', 'onPause()V', 'onStop()V', 'onDestroy()V',
    'onPostResume()V', 'onPostCreate(Landroid/os/Bundle;)V',
    'onSaveInstanceState(Landroid/os/Bundle;)V', 'onRestoreInstanceState(Landroid/os/Bundle;)V',
    'onConfigurationChanged(Landroid/content/res/Configuration;)V', 'onWindowFocusChanged(Z)V',
    'onActivityResult(IILandroid/content/Intent;)V', 'onRequestPermissionsResult(I[Ljava/lang/String;[I)V',
}


def parameter_words(signature):
    params = signature.split('(', 1)[1].split(')', 1)[0]
    tokens = re.findall(r'\[*L[^;]+;|\[*[ZBCSIJFD]', params)
    if ''.join(tokens) != params:
        raise ValueError('Unparsed method descriptor')
    return sum(2 if p in ('J', 'D') else 1 for p in tokens)


def invocation(opcode, owner, signature):
    count = parameter_words(signature)
    return f'    {opcode}/range {{p0 .. p{count}}}, {owner}->{signature}\n'


def patch_activity_text(text, class_name):
    owner = 'L' + class_name.replace('.', '/') + ';'
    superclass = re.search(r'^\.super (L[^\n]+;)', text, re.M)
    if not superclass or 'familyBinaryInitialized' in text:
        raise ValueError(f'Invalid/already-patched Activity {class_name}')
    superclass = superclass.group(1)
    wrappers = []
    patched = []
    creates = 0

    def replace(match):
        nonlocal creates
        head = match.group('head')
        signature = head.split()[-1]
        is_create = signature == 'onCreate(Landroid/os/Bundle;)V'
        is_lifecycle = signature in LIFECYCLE
        is_intent = signature.startswith('onNewIntent(Landroid/content/Intent;') and signature.endswith(')V')
        is_handler = class_name == 'org.telegram.ui.LaunchActivity' and signature.startswith('handleIntent(') and signature.endswith(')Z')
        if not (is_create or is_lifecycle or is_intent or is_handler):
            return match.group(0)
        if ' static ' in ' ' + head + ' ' or ' abstract ' in ' ' + head + ' ':
            raise ValueError(f'Unsupported intercepted method {head}')
        if is_create:
            creates += 1
        method, descriptor = signature.split('(', 1)
        renamed = f'familyBinaryOriginal{len(patched)}_{method}({descriptor}'
        patched.append(signature)
        original_flags = [f for f in head.split()[:-1] if f not in ('public', 'protected', 'private', 'final')]
        original = '.method private ' + ' '.join(original_flags + [renamed]) + '\n' + match.group('body') + '.end method\n'
        wrapper = '.method ' + head + '\n    .locals 1\n'
        if is_create or is_handler or is_intent:
            wrapper += '    invoke-static {}, Lorg/telegram/messenger/family/FamilyAccess;->isAllowed()Z\n    move-result v0\n    if-nez v0, :family_continue\n'
        else:
            wrapper += f'    iget-boolean v0, p0, {owner}->familyBinaryInitialized:Z\n    if-nez v0, :family_initialized\n'
            wrapper += invocation('invoke-super', superclass, signature) + '    return-void\n:family_initialized\n'
            if signature != 'onResume()V':
                wrapper += invocation('invoke-direct', owner, renamed) + '    return-void\n.end method\n'
                wrappers.append(wrapper)
                return original
            wrapper += '    invoke-static {}, Lorg/telegram/messenger/family/FamilyAccess;->isAllowed()Z\n    move-result v0\n    if-nez v0, :family_continue\n'
        if is_handler:
            wrapper += '    const/4 v0, 0x0\n    return v0\n'
        else:
            super_signature = 'onNewIntent(Landroid/content/Intent;)V' if is_intent else signature
            wrapper += invocation('invoke-super', superclass, super_signature)
            wrapper += '    invoke-static/range {p0 .. p0}, Lorg/telegram/messenger/family/FamilyBinaryGuard;->redirect(Landroid/app/Activity;)V\n    return-void\n'
        wrapper += ':family_continue\n'
        if is_handler:
            wrapper += '    invoke-static/range {p0 .. p1}, Lorg/telegram/messenger/family/FamilyFeatures;->open(Landroid/app/Activity;Landroid/content/Intent;)Z\n    move-result v0\n    if-eqz v0, :family_original_intent\n    const/4 v0, 0x1\n    return v0\n:family_original_intent\n'
            if 'Lorg/telegram/messenger/browser/Browser$Progress;' in signature:
                # A deferred internal request must reach Telegram's passcode flow
                # without first exposing the request to plugin intent handlers.
                internal = signature.replace('handleIntent(', 'handleIntentInternal(', 1)
                wrapper += '    if-eqz p1, :family_normal_intent\n    const-string v0, "family_screen"\n    invoke-virtual {p1, v0}, Landroid/content/Intent;->hasExtra(Ljava/lang/String;)Z\n    move-result v0\n    if-eqz v0, :family_normal_intent\n'
                internal_head = re.search(r'^\.method ([^\n]*\b' + re.escape(internal) + r')$', text, re.M)
                if internal_head is None:
                    raise ValueError('Pinned LaunchActivity passcode handler is missing')
                opcode = 'invoke-direct' if 'private ' in internal_head.group(1) else 'invoke-virtual'
                wrapper += invocation(opcode, owner, internal) + '    move-result v0\n    return v0\n:family_normal_intent\n'
        if is_create:
            wrapper += f'    const/4 v0, 0x1\n    iput-boolean v0, p0, {owner}->familyBinaryInitialized:Z\n'
        wrapper += invocation('invoke-direct', owner, renamed)
        wrapper += '    move-result v0\n    return v0\n' if is_handler else '    return-void\n'
        wrappers.append(wrapper + '.end method\n')
        return original

    result = METHOD.sub(replace, text)
    if creates == 0:
        # Inheriting onCreate still requires a gate before entering the superclass.
        synthetic = '.method protected onCreate(Landroid/os/Bundle;)V\n    .locals 0\n' + invocation('invoke-super', superclass, 'onCreate(Landroid/os/Bundle;)V') + '    return-void\n.end method\n'
        result += '\n' + METHOD.sub(replace, synthetic)
    if creates != 1:
        raise ValueError(f'Unexpected onCreate count in {class_name}: {creates}')
    result = result.replace('.super ' + superclass, '.super ' + superclass + '\n\n.field private familyBinaryInitialized:Z', 1)
    return result + '\n' + '\n'.join(wrappers), patched


def patch_application(text):
    native_call = re.compile(r'^(\s*)(invoke-static(?:/range)?) (\{[^\n]+\}), Lorg/telegram/messenger/NativeLoader;->initNativeLibs\(Landroid/content/Context;\)V$', re.M)
    matches = list(native_call.finditer(text))
    if len(matches) != 2 or 'FamilyBinaryGuard;' in text:
        raise ValueError(f'Expected exactly two unpatched NativeLoader calls; found {len(matches)}')
    text = native_call.sub(lambda m: m.group(0) + f'\n{m[1]}{m[2]} {m[3]}, Lorg/telegram/messenger/family/FamilyTelegramPreset;->apply(Landroid/content/Context;)V\n{m[1]}{m[2]} {m[3]}, Lorg/telegram/messenger/family/FamilyNative;->initialize(Landroid/content/Context;)V\n{m[1]}{m[2]} {m[3]}, Lorg/telegram/messenger/family/FamilyBinaryGuard;->install(Landroid/content/Context;)V', text)
    for owner, method in [
        ('org/telegram/messenger/ProxyRotationController', 'init'),
        ('app/exteraless/proxy/ProxyPingController', 'init'),
        ('app/exteraless/links/LinkCleaner', 'preloadIfEnabled'),
    ]:
        pattern = re.compile(r'^\s*invoke-static(?:/range)? \{\}, L' + re.escape(owner) + r';->' + method + r'\(\)V\s*$', re.M)
        text = pattern.sub('    # FamilyGram: optional background network initialization removed', text)
    if re.search(r'invoke-static[^\n]*(?:ProxyRotationController;->init|ProxyPingController;->init|LinkCleaner;->preloadIfEnabled)', text):
        raise ValueError('Optional background initialization path was not removed')
    head = re.compile(r'^\.method public static postInitApplication\(\)V$', re.M)
    if len(head.findall(text)) != 1:
        raise ValueError('postInitApplication signature changed')
    text = head.sub('.method private static familyBinaryOriginalPostInitApplication()V', text)
    text += '''
.method public static postInitApplication()V
    .locals 1
    invoke-static {}, Lorg/telegram/messenger/family/FamilyAccess;->isAllowed()Z
    move-result v0
    if-eqz v0, :family_done
    invoke-static {}, Lorg/telegram/messenger/ApplicationLoader;->familyBinaryOriginalPostInitApplication()V
:family_done
    return-void
.end method
'''
    return patch_boot_defaults(text)


def patch_boot_defaults(text):
    """Apply first-run appearance before upstream caches and disable upstream push bootstrap."""
    found = set()
    def replace(match):
        signature = match.group('head').split()[-1]
        body = match.group('body')
        if signature == 'onCreate()V':
            assignment = re.compile(r'^(\s*)sput-object ([vp][0-9]+), Lorg/telegram/messenger/ApplicationLoader;->applicationContext:Landroid/content/Context;$', re.M)
            if not assignment.search(body):
                raise ValueError('Early applicationContext assignment is missing')
            body = assignment.sub(lambda m: m.group(0) + '\n    invoke-static {' + m[2] + '}, Lorg/telegram/messenger/family/FamilyTelegramPreset;->apply(Landroid/content/Context;)V', body, count=1)
            found.add('early_preset')
        elif signature == 'initPushServices()V':
            body = '    .locals 0\n    # FamilyGram uses its family push service.\n    return-void\n'
            found.add('push')
        else:
            return match.group(0)
        return '.method ' + match.group('head') + '\n' + body + '.end method\n'
    result = METHOD.sub(replace, text)
    if found != {'early_preset', 'push'}:
        raise ValueError('Pinned application defaults hooks are missing: ' + str(found))
    return result


def patch_settings(text):
    owner = 'Lorg/telegram/ui/SettingsActivity;'
    signatures = {
        'fillItems(Ljava/util/ArrayList;Lorg/telegram/ui/Components/UniversalAdapter;)V': 'fill',
        'onClick(Lorg/telegram/ui/Components/UItem;Landroid/view/View;IFF)V': 'click',
    }
    found = set()
    wrappers = []
    def replace(match):
        head = match.group('head')
        signature = head.split()[-1]
        if signature not in signatures:
            return match.group(0)
        if signature in found:
            raise ValueError('Duplicate settings callback')
        found.add(signature)
        renamed = 'familyBinaryOriginal_' + signature
        original = '.method private ' + renamed + '\n' + match.group('body') + '.end method\n'
        wrapper = '.method ' + head + '\n    .locals 1\n'
        if signatures[signature] == 'click':
            wrapper += '    invoke-static/range {p0 .. p1}, Lorg/telegram/messenger/family/FamilyBinarySettings;->onClick(Ljava/lang/Object;Ljava/lang/Object;)Z\n    move-result v0\n    if-eqz v0, :family_original\n    return-void\n:family_original\n'
        wrapper += invocation('invoke-direct', owner, renamed)
        if signatures[signature] == 'fill':
            wrapper += '    invoke-static/range {p0 .. p1}, Lorg/telegram/messenger/family/FamilyBinarySettings;->addRow(Ljava/lang/Object;Ljava/util/ArrayList;)V\n'
        wrappers.append(wrapper + '    return-void\n.end method\n')
        return original
    result = METHOD.sub(replace, text)
    if found != set(signatures):
        raise ValueError('Pinned SettingsActivity adapter signatures differ: ' + str(found))
    return result + '\n' + '\n'.join(wrappers)


def rename_literals(decoded):
    changed = 0
    for folder in decoded.glob('smali*'):
        for path in folder.rglob('*.smali'):
            text = path.read_text(encoding='utf-8')
            if ORIGINAL_PACKAGE in text:
                # Slash-separated Java class/package descriptors are left untouched.
                path.write_text(text.replace(ORIGINAL_PACKAGE, FAMILY_PACKAGE), encoding='utf-8')
                changed += 1
    for path in (decoded / 'res').rglob('*.xml'):
        text = path.read_text(encoding='utf-8')
        if ORIGINAL_PACKAGE in text:
            path.write_text(text.replace(ORIGINAL_PACKAGE, FAMILY_PACKAGE), encoding='utf-8')
    return changed


def split_dense_dex(decoded):
    """The pinned classes5.dex is near its method-reference limit before our hooks."""
    source = find_smali(decoded, 'org.telegram.ui.LaunchActivity').parent
    expected_source = within_work(decoded / 'smali_classes5/org/telegram/ui')
    if source.resolve() != expected_source:
        raise ValueError('Pinned LaunchActivity DEX placement changed')
    target = within_work(decoded / 'smali_classes8/org/telegram/ui')
    target.mkdir(parents=True, exist_ok=True)
    files = sorted(source.glob('LaunchActivity*.smali'))
    if not files:
        raise ValueError('No LaunchActivity definitions to split')
    for path in files:
        old, new = within_work(path), within_work(target / path.name)
        if old.parent != expected_source or new.parent != target or new.exists():
            raise ValueError('Unexpected/colliding DEX split path')
        old.rename(new)
    return {'from': 'classes5.dex', 'to': 'classes8.dex', 'classes': len(files)}


def patch_private_calls(text):
    """Disable private-call P2P while preserving Telegram relay support."""
    allowed = re.compile(r'^\s*iget-boolean ([vp][0-9]+), [vp][0-9]+, Lorg/telegram/tgnet/tl/TL_phone\$PhoneCall;->p2p_allowed:Z\s*$', re.M)
    writes = re.compile(r'^\s*iput-boolean [vp][0-9]+, [vp][0-9]+, Lorg/telegram/tgnet/tl/TL_phone\$PhoneCallProtocol;->udp_p2p:Z\s*$', re.M)
    if len(allowed.findall(text)) != 1 or len(writes.findall(text)) != 3:
        raise ValueError('Pinned private-call P2P configuration differs')
    relay_count = text.count('->udp_reflector:Z')
    text = allowed.sub(lambda m: '\n    # FamilyGram: private calls use relays only.\n    const/16 ' + m[1] + ', 0x0\n', text)
    # These three writes follow freshly constructed TL_phoneCallProtocol objects in
    # requestCall, acceptCall and confirmCall. Their Boolean fields default to false.
    # Removing only the P2P assignment leaves the shared true register and relay flags intact.
    text = writes.sub('\n    # FamilyGram: retain the fresh protocol object\'s udp_p2p=false.\n', text)
    if text.count('->udp_reflector:Z') != relay_count:
        raise ValueError('Relay flags unexpectedly changed')
    return text


def remove_crashlytics_enable(text):
    gap = r'\s*(?:\.line [0-9]+\s*)?'
    pattern = re.compile(
        r'invoke-static \{\}, Lcom/google/firebase/crashlytics/FirebaseCrashlytics;->getInstance\(\)Lcom/google/firebase/crashlytics/FirebaseCrashlytics;'
        + gap + r'move-result-object (?P<instance>[vp][0-9]+)'
        + gap + r'invoke-static \{\}, Ltw/nekomimi/nekogram/utils/AndroidUtil;->shouldEnableCrashlytics\(\)Z'
        + gap + r'move-result (?P<enabled>[vp][0-9]+)'
        + gap + r'invoke-virtual \{(?P=instance), (?P=enabled)\}, Lcom/google/firebase/crashlytics/FirebaseCrashlytics;->setCrashlyticsCollectionEnabled\(Z\)V')
    result, count = pattern.subn('# FamilyGram: Crashlytics bootstrap is disabled with its registrar.', text)
    if count != 1:
        raise ValueError('Pinned Crashlytics bootstrap sequence differs')
    return result


def disable_crash_reporting_methods(text):
    targets = {'putCrashlyticsKeys(Ljava/lang/Throwable;)V', 'reportOutOfMemory()V'}
    found = set()
    def replace(match):
        signature = match.group('head').split()[-1]
        if signature not in targets:
            return match.group(0)
        found.add(signature)
        return '.method ' + match.group('head') + '\n    .locals 0\n    # FamilyGram: preserve local crash logging; no external telemetry.\n    return-void\n.end method\n'
    result = METHOD.sub(replace, text)
    if found != targets or 'FirebaseCrashlytics;->getInstance' in result:
        raise ValueError('Pinned crash reporting method inventory differs')
    return result


def patch_telemetry(decoded):
    for name in ('org.telegram.messenger.ApplicationLoader', 'app.exteraless.settings.OpenExteraOtherActivity'):
        path = find_smali(decoded, name)
        path.write_text(remove_crashlytics_enable(path.read_text(encoding='utf-8')), encoding='utf-8')
    path = find_smali(decoded, 'app.exteraless.crash.CrashLog')
    path.write_text(disable_crash_reporting_methods(path.read_text(encoding='utf-8')), encoding='utf-8')


def force_family_push_provider(text):
    signature = 'getProvider()Lorg/telegram/messenger/PushListenerController$IPushListenerServiceProvider;'
    constructor = 'Lorg/telegram/messenger/PushListenerController$DummyPushProvider;-><init>(Lorg/telegram/messenger/PushListenerController-IA;)V'
    if constructor not in text:
        raise ValueError('Pinned DummyPushProvider synthetic constructor differs')
    found = 0
    def replace(match):
        nonlocal found
        if match.group('head').split()[-1] != signature:
            return match.group(0)
        found += 1
        return '.method ' + match.group('head') + '''
    .locals 2
    sget-object v0, Lorg/telegram/messenger/PushListenerController;->instance:Lorg/telegram/messenger/PushListenerController$IPushListenerServiceProvider;
    instance-of v1, v0, Lorg/telegram/messenger/PushListenerController$DummyPushProvider;
    if-nez v1, :family_provider
    new-instance v0, Lorg/telegram/messenger/PushListenerController$DummyPushProvider;
    const/4 v1, 0x0
    invoke-direct {v0, v1}, ''' + constructor + '''
    sput-object v0, Lorg/telegram/messenger/PushListenerController;->instance:Lorg/telegram/messenger/PushListenerController$IPushListenerServiceProvider;
:family_provider
    return-object v0
.end method
'''
    result = METHOD.sub(replace, text)
    if found != 1:
        raise ValueError('Pinned push provider factory differs')
    return result


def apply_family_branding(decoded):
    pattern = re.compile(r'(<string name="(?:AppName|Page1Title)"(?: [^>]*)?>)[^<]*(</string>)')
    changed = 0
    for path in (decoded / 'res').glob('values*/strings.xml'):
        source = path.read_text(encoding='utf-8')
        branded, count = pattern.subn(r'\g<1>FamilyGram\g<2>', source)
        if count:
            path.write_text(branded, encoding='utf-8')
            changed += count
    if changed == 0:
        raise ValueError('Pinned application title resources are missing')
    locale = find_smali(decoded, 'org.telegram.messenger.LocaleController')
    text = locale.read_text(encoding='utf-8')
    found = 0
    def replace(match):
        nonlocal found
        if match.group('head') != 'public static getAppName()Ljava/lang/String;':
            return match.group(0)
        found += 1
        return '.method public static getAppName()Ljava/lang/String;\n    .locals 1\n    const-string v0, "FamilyGram"\n    return-object v0\n.end method\n'
    text = METHOD.sub(replace, text)
    if found != 1:
        raise ValueError('Pinned application name helper differs')
    locale.write_text(text, encoding='utf-8')
    return changed


def patch_smali(decoded, activities):
    app = find_smali(decoded, 'org.telegram.messenger.ApplicationLoader')
    app.write_text(patch_application(app.read_text(encoding='utf-8')), encoding='utf-8')
    report = {}
    # Gate every manifest Activity, including non-exported PendingIntent/bubble targets.
    # A missing/obfuscated definition is a hard stop, not a silent bypass.
    for row in activities:
        name = row['name']
        path = find_smali(decoded, name)
        patched, methods = patch_activity_text(path.read_text(encoding='utf-8'), name)
        path.write_text(patched, encoding='utf-8')
        report[name] = methods
    launch = report.get('org.telegram.ui.LaunchActivity', [])
    if not any(m.startswith('handleIntent(') for m in launch):
        raise ValueError('No LaunchActivity handleIntent wrappers were intercepted')
    settings = find_smali(decoded, 'org.telegram.ui.SettingsActivity')
    settings.write_text(patch_settings(settings.read_text(encoding='utf-8')), encoding='utf-8')
    calls = find_smali(decoded, 'org.telegram.messenger.voip.VoIPService')
    calls.write_text(patch_private_calls(calls.read_text(encoding='utf-8')), encoding='utf-8')
    factory = find_smali(decoded, 'org.telegram.ui.SettingsActivity$SettingCell$Factory').read_text(encoding='utf-8')
    if 'of(IIIILjava/lang/CharSequence;Ljava/lang/CharSequence;)Lorg/telegram/ui/Components/UItem;' not in factory:
        raise ValueError('Pinned SettingsActivity cell factory differs')
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--apktool', type=Path, required=True)
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--overlay', type=Path, default=ROOT.parent / 'overlay')
    args = parser.parse_args()
    if digest(args.apk) != EXPECTED:
        raise ValueError('Upstream APK does not match the pinned v13.1.1-beta15 SHA-256')
    work = within_work(args.work)
    if work.exists():
        raise ValueError('Work directory already exists; choose a new empty child directory')
    work.mkdir(parents=True)
    decoded = work / 'decoded'
    subprocess.run([str(args.java), '-jar', str(args.apktool), 'd', str(args.apk), '-o', str(decoded)], check=True)
    resource_map = merge_resources(decoded, args.overlay, work / 'generated')
    activities = patch_manifest(decoded)
    hooks = patch_smali(decoded, activities)
    patch_telemetry(decoded)
    provider = find_smali(decoded, 'org.telegram.messenger.PushListenerController')
    provider.write_text(force_family_push_provider(provider.read_text(encoding='utf-8')), encoding='utf-8')
    branding = apply_family_branding(decoded)
    split = split_dense_dex(decoded)
    renamed = rename_literals(decoded)
    report = {'upstream_sha256': EXPECTED, 'resource_ids': resource_map, 'activities': activities,
              'hooks': hooks, 'dex_split': split, 'branded_title_resources': branding, 'renamed_smali_files': renamed, 'package': FAMILY_PACKAGE,
              'status': 'prepared; not built, signed, installed or runtime-verified'}
    (work / 'report.json').write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
    print(f'Prepared {len(hooks)} Activity definitions; native bridge and runtime checks remain mandatory.')


if __name__ == '__main__':
    main()
