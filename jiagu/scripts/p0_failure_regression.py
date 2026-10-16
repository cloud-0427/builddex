"""Verify actual pre-Provider failures survive signed APK updates without clearing data."""
import argparse
import io
import json
import struct
import subprocess
import tarfile
import time
import urllib.request
import zlib
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--apk', type=Path, required=True)
parser.add_argument('--build-tools', type=Path, required=True)
parser.add_argument('--keystore', type=Path, required=True)
parser.add_argument('--directory', type=Path, default=Path('build/p0/failures'))
args = parser.parse_args()
args.directory.mkdir(parents=True, exist_ok=True)
package = 'lows.dgeon.ightr.jiagu'
prefix = ['adb', '-s', args.serial]
timeline = []

def run(cmd, timeout=180):
    result = subprocess.run([str(x) for x in cmd], capture_output=True, timeout=timeout)
    timeline.append({'at': time.time(), 'command': [str(x) for x in cmd], 'exit': result.returncode,
                     'output': result.stdout.decode(errors='replace')[-2000:], 'error': result.stderr.decode(errors='replace')[-2000:]})
    if result.returncode:
        raise RuntimeError(timeline[-1])
    return result.stdout

def install(apk):
    return run(['python', 'scripts/p0_install.py', '--serial', args.serial, apk])

expected = {'missing': ('LOCAL_PAYLOAD_READ', 'LOCAL_PAYLOAD_MISSING'),
            'format': ('LOCAL_PAYLOAD_READ', 'LOCAL_PAYLOAD_FORMAT_UNSUPPORTED'),
            'auth': ('PAYLOAD_DECRYPT', 'LOCAL_PAYLOAD_AUTH_FAILED'),
            'dex': ('BUSINESS_DEX_PARSE', 'LOCAL_PAYLOAD_DEX_TABLE_INVALID'),
            'application': ('REAL_APPLICATION_CREATE', 'LOCAL_APPLICATION_CREATE_FAILED')}
failures = {}
report = {}
try:
    for fault, (stage, code) in expected.items():
        unsigned = args.directory / (fault + '-unsigned.apk')
        aligned = args.directory / (fault + '.apk')
        run(['python', 'scripts/p0_mutate_apk.py', args.apk, unsigned, '--fault', fault])
        run([args.build_tools / 'zipalign.exe', '-f', '-P', '16', '4', unsigned, aligned])
        run([args.build_tools / 'apksigner.bat', 'sign', '--ks', args.keystore,
             '--ks-pass', 'pass:android', '--key-pass', 'pass:android', aligned])
        install(aligned)
        run(prefix + ['shell', 'am', 'force-stop', package])
        run(prefix + ['shell', 'am', 'start', '-n', package + '/.MainActivity'])
        time.sleep(2)
        run(prefix + ['shell', 'am', 'force-stop', package])
        archive = run(prefix + ['exec-out', 'run-as', package, 'tar', '-cf', '-', 'no_backup/jiagu-startup-v1'])
        (args.directory / (fault + '.tar')).write_bytes(archive)
        observed = []
        with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
            for member in tar.getmembers():
                if not member.name.endswith('.journal'):
                    continue
                data = tar.extractfile(member).read()
                at = 0
                while at + 16 <= len(data):
                    magic, version, length = struct.unpack_from('>III', data, at)
                    assert magic == 0x4A475331 and version == 1 and length <= 8192
                    if at + length + 16 > len(data):
                        break
                    payload = data[at+12:at+12+length]
                    assert zlib.crc32(payload) == struct.unpack_from('>I', data, at+12+length)[0]
                    e = json.loads(payload)
                    if e['event'] == stage and e['status'] == 'FAILED' and e['resultCode'] == code:
                        failures[e['telemetryEventId']] = e
                        observed.append(e['telemetryEventId'])
                    at += length + 16
        assert observed, (fault, stage, code)
        report[fault] = {'result': 'PASS', 'stage': stage, 'code': code, 'ids': observed}
        print(fault, 'failure persisted', flush=True)
finally:
    install(args.apk)
    run(prefix + ['reverse', 'tcp:18765', 'tcp:18765'])
    run(prefix + ['shell', 'am', 'start', '-n', package + '/.MainActivity'])
    (args.directory / 'timeline.json').write_text(json.dumps(timeline, ensure_ascii=False, indent=2), encoding='utf-8')

deadline = time.monotonic() + 120
while time.monotonic() < deadline:
    state = json.load(urllib.request.urlopen('http://127.0.0.1:18765/state', timeout=5))
    received = {e['telemetryEventId']: e for e in state['events']}
    if set(failures) <= set(received):
        for identity, payload in failures.items():
            assert received[identity] == payload, identity
        report['replay_after_repair'] = {'result': 'PASS', 'failed_events': len(failures)}
        break
    time.sleep(1)
else:
    raise AssertionError('Early failure replay incomplete')
(args.directory / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
(args.directory / 'receiver.json').write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
