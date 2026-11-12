"""Port the existing inline Python and speech hooks to the verified 13.2.1 CI APK.

The full unmodified class hashes are checked before reusing the existing wrapper
generators. Native Telegram bodies are retained under the same saved names used
by preview13, including the Premium transcription path.
"""
import argparse
import hashlib
import json
from pathlib import Path

from patch_inline_python import METHOD, PIN as INLINE_SIGNATURES, patch as patch_inline
from patch_voice_transcription import PINS as VOICE_SIGNATURES, patch as patch_voice


CLASS_PINS = {
    'ChatMessageCell': '500bd9701e909cbc1032a1090f6d1e3aad675a611562ff498c88919abc9a90e7',
    'TranscribeButton': '8ca4f040360d437595ac364286a7480a6d11f878d8fc060b20d818476f6e3559',
}
CLASS_FOLDERS = {'ChatMessageCell': 'Cells', 'TranscribeButton': 'Components'}


def method_pins(source, signatures):
    result = {}
    methods = list(METHOD.finditer(source))
    for signature in signatures:
        matches = [method.group(0) for method in methods
                   if method.group(0).splitlines()[0].endswith(' ' + signature)]
        if len(matches) != 1:
            raise ValueError('Expected one native method: ' + signature)
        result[signature] = hashlib.sha256(matches[0].encode()).hexdigest()
    return result


def prepare(decoded):
    """Validate both original classes and return all changes without writing."""
    decoded = Path(decoded)
    if set(CLASS_PINS) != set(CLASS_FOLDERS):
        raise ValueError('The verified 13.2.1 class pins have not been installed')
    originals = {}
    for name, folder in CLASS_FOLDERS.items():
        paths = list(decoded.glob('smali*/org/telegram/ui/' + folder + '/' + name + '.smali'))
        if len(paths) != 1:
            raise ValueError('Expected one original class: ' + name)
        raw = paths[0].read_bytes()
        if hashlib.sha256(raw).hexdigest() != CLASS_PINS[name]:
            raise ValueError('Verified 13.2.1 class differs: ' + name)
        originals[name] = (paths[0], raw.decode('utf-8'))
    changes = []
    for name, (path, source) in originals.items():
        if name == 'ChatMessageCell':
            source = patch_inline(source, method_pins(source, INLINE_SIGNATURES))
        pins = {name: method_pins(source, VOICE_SIGNATURES[name])}
        source = patch_voice(source, name, pins)
        changes.append((path, source))
    return changes


def apply(decoded):
    changes = prepare(decoded)
    for path, source in changes:
        path.write_text(source, encoding='utf-8', newline='\n')
    return {path.name: hashlib.sha256(source.encode()).hexdigest() for path, source in changes}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--decoded', type=Path, required=True)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    if args.output and args.apply:
        raise SystemExit('Choose separate output or apply')
    changes = prepare(args.decoded)
    for path, source in changes:
        if args.apply:
            path.write_text(source, encoding='utf-8', newline='\n')
        if args.output:
            args.output.mkdir(parents=True, exist_ok=True)
            (args.output / path.name).write_text(source, encoding='utf-8', newline='\n')
    print(json.dumps({'verified_classes': len(changes), 'applied': args.apply,
                      'sha256': {path.name: hashlib.sha256(source.encode()).hexdigest()
                                 for path, source in changes}}))
