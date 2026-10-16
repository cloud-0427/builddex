"""Two-axis attribution from a regression snapshot and the local receiver's export."""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('snapshot', type=Path)
parser.add_argument('receiver', type=Path)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
rows = json.loads(args.snapshot.read_text(encoding='utf-8'))
remote = {e['telemetryEventId'] for e in json.loads(args.receiver.read_text(encoding='utf-8'))['events']}
stages = ['STARTUP_ATTEMPT', 'LOCAL_PAYLOAD_READ', 'PAYLOAD_DECRYPT', 'BUSINESS_DEX_PARSE',
          'BUSINESS_CLASSLOADER_CREATE', 'REAL_APPLICATION_CREATE']
sessions = {}
for identity, row in rows.items():
    sessions.setdefault(row['event']['sessionId'], []).append(row)
gaps = []
for session, events in sessions.items():
    actual = {row['event']['event']: row for row in events if row['event']['recordType'] == 'STAGE'}
    checkpoints = {row['event']['event'] for row in events if row['event']['recordType'] == 'CHECKPOINT'}
    earlier_failure = None
    for stage in stages + ['EVENT_REPORTER_READY', 'FIRST_ACTIVITY_RESUMED']:
        row = actual.get(stage)
        e = row['event'] if row else {}
        received = row and e['telemetryEventId'] in remote
        if received and e['status'] in ('STARTED', 'SUCCEEDED'):
            continue
        if e.get('status') == 'FAILED':
            execution = 'CONFIRMED_FAILURE'
            earlier_failure = e
        elif e.get('status') in ('SUCCEEDED', 'STARTED'):
            execution = 'RECORDED'
        elif earlier_failure:
            execution = 'NOT_REACHED'
        elif stage in stages and any(actual.get(later, {}).get('event', {}).get('status') == 'SUCCEEDED'
                                     for later in stages[stages.index(stage)+1:]):
            execution = 'INSTRUMENTATION_UNKNOWN'
        elif stage in checkpoints:
            execution = 'INTERRUPTED_UNKNOWN'
        else:
            execution = 'BOUNDARY_UNKNOWN'
        delivery = row['delivery'] if row else 'DELIVERY_UNKNOWN'
        if not row and execution == 'NOT_REACHED':
            delivery = 'NOT_GENERATED'
        if delivery == 'ACKED' and not received:
            delivery = 'ANALYTICS_GAP'
        gaps.append({'gapId': session + ':' + stage, 'sessionId': session, 'stage': stage,
                     'execution': execution, 'delivery': delivery, 'resultCode': e.get('resultCode', ''),
                     'blockedBy': {'stage': earlier_failure['event'], 'resultCode': earlier_failure['resultCode']} if earlier_failure and execution == 'NOT_REACHED' else None,
                     'evidence': 'LOCAL_SNAPSHOT_AND_RECEIVER',
                     'note': '1005/1006 为并行观测；无前台资格证据时不推断界面失败'})
args.output.write_text(json.dumps(gaps, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({'sessions': len(sessions), 'gaps': len(gaps)}, ensure_ascii=False))
