"""Structural device-IPA checks. These do not establish signing or runtime readiness."""
from pathlib import PurePosixPath
import plistlib
import struct
import zipfile

def macho(data):
    if len(data) < 32:
        raise ValueError('Truncated Mach-O')
    magic, cpu, subtype, kind, count, size, flags, reserved = struct.unpack_from('<8I', data)
    if magic != 0xfeedfacf or cpu != 0x100000c:
        raise ValueError('Expected thin arm64 Mach-O')
    if count > 10000 or size > len(data) - 32:
        raise ValueError('Invalid Mach-O load commands')
    offset, platform, encrypted = 32, None, False
    for _ in range(count):
        command, length = struct.unpack_from('<II', data, offset)
        if length < 8 or offset + length > 32 + size:
            raise ValueError('Invalid Mach-O command boundary')
        if command == 0x32:
            if length < 24: raise ValueError('Truncated build version')
            platform = struct.unpack_from('<I', data, offset + 8)[0]
        if command == 0x2c:
            if length < 24: raise ValueError('Truncated encryption command')
            encrypted |= struct.unpack_from('<I', data, offset + 16)[0] != 0
        offset += length
    if offset != 32 + size or platform != 2 or encrypted:
        raise ValueError('Not an unencrypted iOS device Mach-O')

def verify(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(set(names)) != len(names): raise ValueError('Duplicate archive paths')
        for item in archive.infolist():
            p = PurePosixPath(item.filename)
            if p.is_absolute() or '..' in p.parts or '\\' in item.filename:
                raise ValueError('Unsafe archive path')
            if item.flag_bits & 1: raise ValueError('Encrypted ZIP entry')
        roots = [n for n in names if n.startswith('Payload/') and n.endswith('.app/Info.plist') and n.count('/') == 2]
        if len(roots) != 1: raise ValueError('Expected exactly one main application')
        info_path = roots[0]; base = info_path[:-len('Info.plist')]
        info = plistlib.loads(archive.read(info_path))
        if info.get('CFBundleIdentifier') != 'com.salatik.tgru': raise ValueError('Bundle identifier mismatch')
        exe = info.get('CFBundleExecutable', '')
        if not exe or '/' in exe or '\\' in exe: raise ValueError('Invalid executable name')
        if any(n.startswith(base + 'PlugIns/') or n.startswith(base + 'Watch/') or n.endswith('.mobileprovision') for n in names):
            raise ValueError('Unexpected extension, Watch app or provisioning profile')
        binaries = [base + exe]
        for n in names:
            if n.startswith(base + 'Frameworks/') and n.endswith('.framework/Info.plist'):
                framework = plistlib.loads(archive.read(n))
                name = framework.get('CFBundleExecutable', '')
                if not name or '/' in name: raise ValueError('Invalid framework executable')
                binaries.append(n[:-len('Info.plist')] + name)
        for name in binaries:
            with archive.open(name) as stream: macho(stream.read(2 * 1024 * 1024))
        return {'bundle_id': info['CFBundleIdentifier'], 'version': info.get('CFBundleShortVersionString'),
                'build': info.get('CFBundleVersion'), 'architecture': 'arm64', 'platform': 'iOS-device',
                'checked_macho_binaries': len(binaries), 'extensions': 0,
                'signed_for_installation': False, 'device_login_tested': False}
