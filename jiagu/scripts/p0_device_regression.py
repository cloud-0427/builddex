"""Exercise the protected app against p0_event_server.py; preserve per-ID evidence."""
import argparse
import io
import json
import sqlite3
import struct
import subprocess
import tarfile
import time
import urllib.request
import zlib
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--directory', default='build/p0/device')
parser.add_argument('--reboot', action='store_true', help='Also reboot the connected test device with committed pending events')
args = parser.parse_args()
root = Path(args.directory)
root.mkdir(parents=True, exist_ok=True)
package = 'lows.dgeon.ightr.jiagu'
timeline = []

def adb(*cmd, timeout=45, check=True):
    result = subprocess.run(['adb', '-s', args.serial, *cmd], capture_output=True, timeout=timeout)
    timeline.append({'at': time.time(), 'command': list(cmd), 'exit': result.returncode,
                     'stdout': result.stdout.decode(errors='replace')[:1000],
                     'stderr': result.stderr.decode(errors='replace')[:1000]})
    if check and result.returncode:
        raise RuntimeError(timeline[-1])
    return result.stdout

def state():
    for attempt in range(3):
        try:
            return json.load(urllib.request.urlopen('http://127.0.0.1:18765/state', timeout=10))
        except (TimeoutError, urllib.error.URLError) as error:
            timeline.append({'at': time.time(), 'receiver_poll_error': type(error).__name__, 'attempt': attempt + 1})
            if attempt == 2:
                raise
            time.sleep(1)

def mode(value):
    req = urllib.request.Request('http://127.0.0.1:18765/control',
                                 json.dumps({'mode': value}).encode(), {'Content-Type': 'application/json'})
    urllib.request.urlopen(req, timeout=5).close()
    timeline.append({'at': time.time(), 'mode': value})

def launch():
    adb('shell', 'am', 'force-stop', package)
    adb('shell', 'am', 'start', '-n', package + '/.MainActivity')

def wait_for(predicate, seconds=90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        current = state()
        if predicate(current):
            return current
        time.sleep(1)
    raise AssertionError('Receiver condition timed out')

def snapshot(name):
    adb('shell', 'am', 'force-stop', package)
    archive = adb('exec-out', 'run-as', package, 'tar', '-cf', '-', 'no_backup/jiagu-startup-v1')
    (root / (name + '.tar')).write_bytes(archive)
    rows = {}
    database = None
    sidecars = {}
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        for member in tar.getmembers():
            if not member.isfile():
                continue
            data = tar.extractfile(member).read()
            if member.name.endswith('events.db'):
                database = data
            elif member.name.endswith(('events.db-journal', 'events.db-wal')):
                sidecars[member.name.rsplit('events.db', 1)[1]] = data
            elif member.name.endswith('.journal'):
                position = 0
                while position + 16 <= len(data):
                    magic, version, length = struct.unpack_from('>III', data, position)
                    assert magic == 0x4A475331 and version == 1 and length <= 8192
                    if position + length + 16 > len(data):
                        break
                    payload = data[position + 12:position + 12 + length]
                    crc, = struct.unpack_from('>I', data, position + 12 + length)
                    assert zlib.crc32(payload) == crc
                    e = json.loads(payload)
                    previous = rows.get(e['telemetryEventId'])
                    if previous:
                        assert previous['event'] == e
                    else:
                        rows[e['telemetryEventId']] = {'event': e, 'delivery': 'JOURNALED'}
                    position += length + 16
    if database is not None:
        dbfile = root / (name + '.db')
        dbfile.write_bytes(database)
        for suffix, data in sidecars.items():
            Path(str(dbfile) + suffix).write_bytes(data)
        # Include the hot rollback journal, so SQLite can recover a transaction interrupted
        # by the controller rather than reading an inconsistent standalone database file.
        with sqlite3.connect(dbfile) as db:
            for payload, delivery, attempts, error in db.execute('SELECT payload,state,attempts,error FROM records'):
                e = json.loads(payload)
                previous = rows.get(e['telemetryEventId'])
                if previous:
                    assert previous['event'] == e
                rows[e['telemetryEventId']] = {'event': e, 'delivery': delivery, 'attempts': attempts, 'error': error}
    (root / (name + '.json')).write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding='utf-8')
    return rows

def assert_received(rows, received):
    remote = {e['telemetryEventId']: e for e in received['events']}
    expected = {identity: row['event'] for identity, row in rows.items() if row['event']['recordType'] != 'CHECKPOINT'}
    assert set(expected) <= set(remote), sorted(set(expected) - set(remote))
    for identity, event in expected.items():
        assert remote[identity] == event, identity
    return len(expected)

report = {}
try:
    adb('reverse', 'tcp:18765', 'tcp:18765')
    mode('accept')
    before = {e['sessionId'] for e in state()['events']}
    launch()
    normal = wait_for(lambda s: any(e['eventId'] == '1006' and e['sessionId'] not in before for e in s['events']))
    report['normal'] = {'result': 'PASS', 'received': len(normal['events'])}

    # Actual connection failure: remove adb reverse, not just a synthetic Java exception.
    adb('reverse', '--remove', 'tcp:18765')
    launch()
    time.sleep(4)
    offline = snapshot('offline-killed')
    pending = [r for r in offline.values() if r.get('delivery') == 'PENDING']
    assert pending and any(r.get('attempts', 0) > 0 for r in pending)
    adb('reverse', 'tcp:18765', 'tcp:18765')
    launch()
    ids = {i for i, r in offline.items() if r['event']['recordType'] != 'CHECKPOINT'}
    restored = wait_for(lambda s: ids <= {e['telemetryEventId'] for e in s['events']})
    report['offline_kill_restart'] = {'result': 'PASS', 'reconciled': assert_received(offline, restored), 'pending_at_kill': len(pending)}

    mode('ackdrop')
    before = {e['sessionId'] for e in state()['events']}
    launch()
    duplicate = wait_for(lambda s: any(sum(1 for i, result in s['attempts'] if i == e['telemetryEventId']) >= 2
                                        for e in s['events'] if e['sessionId'] not in before))
    uncertain = snapshot('ack-lost-killed')
    mode('accept')
    launch()
    ids = {i for i, r in uncertain.items() if r['event']['recordType'] != 'CHECKPOINT'}
    restored = wait_for(lambda s: ids <= {e['telemetryEventId'] for e in s['events']})
    report['ack_loss_duplicate_restart'] = {'result': 'PASS', 'reconciled': assert_received(uncertain, restored),
                                          'duplicate_requests': len(restored['attempts']) - len({i for i, _ in restored['attempts']})}
    final = snapshot('final')
    final_ids = {i for i, r in final.items() if r['event']['recordType'] != 'CHECKPOINT'}
    # The snapshot can include newly produced events from the recovery launch itself.
    # Give those events a further runtime opportunity before asserting delivery.
    launch()
    final_state = wait_for(lambda s: final_ids <= {e['telemetryEventId'] for e in s['events']})
    report['final'] = {'result': 'PASS', 'reconciled': assert_received(final, final_state)}
    if args.reboot:
        adb('reverse', '--remove', 'tcp:18765')
        launch()
        time.sleep(3)
        pending_reboot = snapshot('pre-device-reboot')
        assert any(r['delivery'] == 'PENDING' for r in pending_reboot.values())
        print('Rebooting test device with committed pending events', flush=True)
        adb('reboot')
        adb('wait-for-device', timeout=120)
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if adb('shell', 'getprop', 'sys.boot_completed').strip() == b'1':
                break
            time.sleep(2)
        else:
            raise AssertionError('Device boot did not complete')
        adb('reverse', 'tcp:18765', 'tcp:18765')
        launch()
        ids = {i for i, r in pending_reboot.items() if r['event']['recordType'] != 'CHECKPOINT'}
        rebooted = wait_for(lambda s: ids <= {e['telemetryEventId'] for e in s['events']}, seconds=120)
        report['device_reboot'] = {'result': 'PASS', 'reconciled': assert_received(pending_reboot, rebooted)}
    (root / 'receiver.json').write_text(json.dumps(state(), ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
finally:
    mode('accept')
    adb('reverse', 'tcp:18765', 'tcp:18765', check=False)
    (root / 'timeline.json').write_text(json.dumps(timeline, ensure_ascii=False, indent=2), encoding='utf-8')
    (root / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
