"""Compile the real gate for iOS Simulator; this does not build the Telegram application."""
from pathlib import Path
import platform
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def main():
    if platform.system() != 'Darwin' or platform.machine() != 'arm64':
        raise ValueError('Apple Silicon macOS SDK required')
    sdk = subprocess.check_output(['xcrun', '--sdk', 'iphonesimulator', '--show-sdk-path'], text=True).strip()
    with tempfile.TemporaryDirectory(prefix='tg-ru-native-gate-') as directory:
        output = Path(directory)
        common = ['xcrun', 'swiftc', '-swift-version', '5', '-target', 'arm64-apple-ios15.0-simulator', '-sdk', sdk]
        for module, folder in [('FamilyCore', 'family-core/Sources/FamilyCore'),
                               ('FamilyTransport', 'transport/Sources/Core')]:
            run(*common, '-parse-as-library', '-emit-module', '-module-name', module,
                '-emit-module-path', output / (module + '.swiftmodule'),
                *sorted((ROOT / folder).glob('*.swift')))
        for mode in [[], ['-D', 'FAMILY_COMPILE_ONLY']]:
            run(*common, '-typecheck', '-module-name', 'FamilyUI', '-I', output, *mode,
                *sorted((ROOT / 'family-ui/Sources/FamilyUI').glob('*.swift')))
    print('NATIVE_GATE_IOS_SIMULATOR_TYPECHECK_PASS: own-api and compile-only; real Core/lease')
    print('Transport Apple adapter and full Telegram linking remain separate build requirements')


if __name__ == '__main__':
    main()
