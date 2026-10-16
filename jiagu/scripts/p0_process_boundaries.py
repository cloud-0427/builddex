"""Real process deaths at journal commit and SQLite transaction boundaries (test APK)."""
import argparse
import json
import subprocess
import uuid
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument('--serial',required=True)
parser.add_argument('--directory',type=Path,default=Path('build/p0/process-boundaries'))
args=parser.parse_args()
args.directory.mkdir(parents=True,exist_ok=True)
stages=['LOCAL_PAYLOAD_READ','PAYLOAD_DECRYPT','BUSINESS_DEX_PARSE','BUSINESS_CLASSLOADER_CREATE','REAL_APPLICATION_CREATE']
report=[]
for stage in stages:
    for phase in ['enter','terminal','half','transaction']:
        session=str(uuid.uuid4())
        base=['adb','-s',args.serial,'shell','am','instrument','-w','-e','session',session,'-e','stage',stage,'-e','phase',phase,'-e','action']
        component='io.github.xjc.jiagu.local.test/io.github.xjc.jiagu.local.LocalRecoveryInstrumentation'
        killed=subprocess.run(base+['write',component],capture_output=True,timeout=30)
        before=killed.stdout.decode(errors='replace')
        assert 'SYNCED '+session in before,before
        recovered=subprocess.run(base+['recover',component],capture_output=True,timeout=30)
        after=recovered.stdout.decode(errors='replace')
        assert 'PASS process-death recovery '+session in after,after
        item={'session':session,'stage':stage,'phase':phase,'result':'PASS','kill_output':before,'recovery_output':after}
        report.append(item)
        (args.directory/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
        print(stage,phase,'PASS',flush=True)
