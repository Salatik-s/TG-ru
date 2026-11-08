"""Run the actual notification-state method against controlled Telegram classes.

No device, account, server, notification permission or network is involved.
"""
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path('C:/Users/andre/AppData/Local/Programs/Android Studio/jbr/bin')
source = (ROOT / 'overlay/java/org/telegram/messenger/family/FamilyPushService.java').read_text('utf-8')
start = source.index('    private static boolean telegramConnected() {')
cursor = source.index('{', start) + 1
depth = 1
while depth:
    if source[cursor] == '{': depth += 1
    elif source[cursor] == '}': depth -= 1
    cursor += 1
method = source[start:cursor].replace('private static boolean', 'public static boolean', 1)
texts = {e.get('name'): e.text for e in ET.parse(ROOT / 'overlay/res/values/fg_push_strings.xml').getroot()}
assert texts == {'fg_push_title': 'TG-ru', 'fg_push_connected': 'Соединено с Telegram', 'fg_push_connecting': 'Подключение к Telegram…'}
assert '"familygram_push_connection"' in source  # Existing notification-channel choices survive.
assert 'NotificationManager.IMPORTANCE_LOW' in source and 'Notification.PRIORITY_MIN' in source
assert 'notificationHandler.removeCallbacks(updateNotification)' in source
assert 'notificationHandler.postDelayed(this, 2_000)' in source
assert 'shownConnectionText = R.string.fg_push_connecting' in source
assert 'FamilyPush.state' not in method
files = {
 'org/telegram/messenger/UserConfig.java': 'package org.telegram.messenger; public class UserConfig { public static int selectedAccount=0; public static int MAX_ACCOUNT_COUNT=3; }',
 'org/telegram/tgnet/ConnectionsManager.java': '''package org.telegram.tgnet;
public class ConnectionsManager {
 public static final int ConnectionStateConnected=3;
 public static int[] states={0,0,0}; public static boolean fail;
 private final int account; private ConnectionsManager(int a){account=a;}
 public static ConnectionsManager getInstance(int a){if(fail)throw new IllegalStateException();return new ConnectionsManager(a);}
 public int getConnectionState(){return states[account];}
}''',
 'FamilyPush.java': 'public class FamilyPush { static boolean allowed=true; static boolean authorized(){return allowed;} }',
 'Harness.java': 'public class Harness {\n' + method + '''
 static void check(boolean expected){if(telegramConnected()!=expected)throw new AssertionError();}
 public static void main(String[] args){
  for(int s=0;s<=5;s++){org.telegram.tgnet.ConnectionsManager.states[0]=s;check(s==3);}
  org.telegram.tgnet.ConnectionsManager.states[2]=3;org.telegram.messenger.UserConfig.selectedAccount=2;check(true);
  org.telegram.messenger.UserConfig.selectedAccount=-1;check(false);
  org.telegram.messenger.UserConfig.selectedAccount=3;check(false);
  org.telegram.messenger.UserConfig.selectedAccount=2;FamilyPush.allowed=false;check(false);
  FamilyPush.allowed=true;org.telegram.tgnet.ConnectionsManager.fail=true;check(false);
  System.out.println("PASS: 11 real-method state/lease/selection/reflection cases; quiet channel and cleanup checks");
 }
}'''
}
with tempfile.TemporaryDirectory(prefix='familygram-push-test-') as folder:
    root = Path(folder)
    for name, value in files.items():
        path = root / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(value, encoding='utf-8')
    subprocess.run([str(JAVA/'javac.exe'), '--release', '17', '-encoding', 'UTF-8', '-d', str(root), *[str(root/name) for name in files]], check=True)
    subprocess.run([str(JAVA/'java.exe'), '-cp', str(root), 'Harness'], check=True)
