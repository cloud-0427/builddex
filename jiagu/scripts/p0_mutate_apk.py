"""Make an unsigned fault APK for debug-only device regression; re-sign separately.

No production artifact is overwritten. DEX corruption reuses the installed cryptography
test tool; this is not an Android or project runtime dependency.
"""
import argparse
import json
import struct
import base64
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('source', type=Path)
parser.add_argument('output', type=Path)
parser.add_argument('--fault', choices=['missing', 'format', 'auth', 'dex', 'application'], required=True)
args = parser.parse_args()
assert args.source.resolve() != args.output.resolve()
asset = 'assets/jiagu/local-payload.jgl'
with zipfile.ZipFile(args.source) as source, zipfile.ZipFile(args.output, 'w') as output:
    changed = False
    for entry in source.infolist():
        if entry.filename.startswith('META-INF/') and entry.filename.upper().endswith(('.SF', '.RSA', '.DSA', '.EC', 'MANIFEST.MF')):
            continue
        data = source.read(entry)
        if entry.filename == asset and args.fault != 'application':
            changed = True
            if args.fault == 'missing':
                continue
            if args.fault == 'format':
                data = b'BAD!' + data[4:]
            elif args.fault == 'auth':
                data = data[:-1] + bytes([data[-1] ^ 1])
            elif args.fault == 'dex':
                from cryptography.hazmat.primitives.ciphers.aead import AESGCM
                import hashlib
                config_length, cipher_length = struct.unpack_from('>II', data, 8)
                config_bytes = data[16:16 + config_length]
                config = json.loads(config_bytes)
                def decode(key):
                    value = config[key]
                    return base64.urlsafe_b64decode(value + '=' * (-len(value) % 4))
                key = bytes(a ^ b for a, b in zip(decode('keyPartA'), decode('keyPartB')))
                plain = AESGCM(key).decrypt(decode('nonce'), data[16 + config_length:], b'JIAGU-LOCAL-ASSET-V1' + config_bytes)
                # Corrupt JG4 table magic, but keep GCM and SHA valid to reach DEX parsing.
                plain = b'BAD!' + plain[4:]
                config['plaintextSha256'] = base64.urlsafe_b64encode(hashlib.sha256(plain).digest()).decode().rstrip('=')
                new_config = json.dumps(config, separators=(',', ':')).encode()
                encrypted = AESGCM(key).encrypt(decode('nonce'), plain, b'JIAGU-LOCAL-ASSET-V1' + new_config)
                data = data[:8] + struct.pack('>II', len(new_config), len(encrypted)) + new_config + encrypted
        if entry.filename == 'AndroidManifest.xml' and args.fault == 'application':
            for encoding in ('utf-8', 'utf-16le'):
                original = 'lows.dgeon.ightr.jiagu.Application'.encode(encoding)
                replacement = 'lows.dgeon.ightr.jiagu.Zpplication'.encode(encoding)
                if original in data:
                    data = data.replace(original, replacement)
                    changed = True
        output.writestr(entry, data)
    assert changed, 'fault target not found'
print(args.output)
