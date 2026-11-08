"""Inspect ordinary DEX class definitions without executing the APK."""
import struct
import zipfile


def classes(data):
    if len(data) < 112 or data[:4] != b'dex\n' or data[7] != 0:
        raise ValueError('Invalid DEX header')
    strings_count, strings_off, types_count, types_off = struct.unpack_from('<IIII', data, 56)
    classes_count, classes_off = struct.unpack_from('<II', data, 96)
    for index in range(classes_count):
        type_index = struct.unpack_from('<I', data, classes_off + index * 32)[0]
        if type_index >= types_count:
            raise ValueError('Invalid DEX class type index')
        string_index = struct.unpack_from('<I', data, types_off + type_index * 4)[0]
        if string_index >= strings_count:
            raise ValueError('Invalid DEX class string index')
        offset = struct.unpack_from('<I', data, strings_off + string_index * 4)[0]
        for _ in range(5):
            byte = data[offset]
            offset += 1
            if not byte & 128:
                break
        else:
            raise ValueError('Invalid DEX string length')
        end = data.index(b'\0', offset)
        yield data[offset:end]


def verify_unique(apk):
    owners = {}
    dex_count = 0
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not (name.startswith('classes') and name.endswith('.dex') and '/' not in name):
                continue
            dex_count += 1
            for descriptor in classes(archive.read(name)):
                if descriptor in owners:
                    raise ValueError(f'Duplicate class {descriptor!r} in {owners[descriptor]} and {name}')
                owners[descriptor] = name
    required = [b'Lorg/telegram/messenger/family/' + name + b';' for name in (
        b'FamilyGateActivity', b'FamilySettingsActivity', b'FamilyAccess', b'FamilyNative',
        b'FamilyFeatures', b'FamilyPush', b'FamilyPushService', b'FamilyBinaryGuard',
        b'FamilyBinarySettings', b'FamilyTelegramPreset')]
    if any(name not in owners for name in required):
        raise ValueError('A required FamilyGram class is missing')
    return {'dex_files': dex_count, 'unique_classes': len(owners)}
