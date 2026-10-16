"""Resume a recorded device reboot after manual USB authorization/unlock."""
import argparse
import json
import subprocess
import time
import urllib.request
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('--serial',required=True)
p.add_argument('--directory',type=Path,default=Path('build/p0/device-reboot'))
a=p.parse_args()
prefix=['adb','-s',a.serial]
subprocess.run(prefix+['reverse','tcp:18765','tcp:18765'],check=True)
subprocess.run(prefix+['shell','am','start','-n','lows.dgeon.ightr.jiagu/.MainActivity'],check=True)
rows=json.loads((a.directory/'pre-device-reboot.json').read_text(encoding='utf-8'))
expected={i:r['event'] for i,r in rows.items() if r['event']['recordType']!='CHECKPOINT'}
deadline=time.monotonic()+120
while time.monotonic()<deadline:
    state=json.load(urllib.request.urlopen('http://127.0.0.1:18765/state',timeout=5))
    received={e['telemetryEventId']:e for e in state['events']}
    if set(expected)<=set(received):
        for identity,event in expected.items():
            assert event==received[identity],identity
        report=json.loads((a.directory/'report.json').read_text(encoding='utf-8'))
        report['device_reboot']={'result':'PASS','reconciled':len(expected),'manual_usb_authorization':True}
        (a.directory/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
        (a.directory/'receiver.json').write_text(json.dumps(state,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps(report['device_reboot']),flush=True)
        break
    time.sleep(1)
else:
    raise SystemExit('Reboot replay incomplete')
