"""Verify the minified Release app works with telemetry off and leaves the outbox untouched."""
import argparse
import hashlib
import io
import subprocess
import tarfile
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('--serial',required=True)
p.add_argument('--release-apk',type=Path,required=True)
p.add_argument('--test-apk',type=Path,required=True)
p.add_argument('--restore-debug-apk',type=Path,required=True)
p.add_argument('--directory',type=Path,default=Path('build/p0/disabled'))
a=p.parse_args()
a.directory.mkdir(parents=True,exist_ok=True)
prefix=['adb','-s',a.serial]
package='lows.dgeon.ightr.jiagu'
def run(cmd):
    r=subprocess.run([str(c) for c in cmd],capture_output=True,timeout=180)
    if r.returncode:
        raise RuntimeError(r.stdout.decode(errors='replace')+r.stderr.decode(errors='replace'))
    return r.stdout
def install(apk):
    return run(['python','scripts/p0_install.py','--serial',a.serial,apk])
run(prefix+['shell','am','force-stop',package])
archive=run(prefix+['exec-out','run-as',package,'tar','-cf','-','no_backup/jiagu-startup-v1'])
(a.directory/'before.tar').write_bytes(archive)
files={}
with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
    for member in tar.getmembers():
        if member.isfile(): files[Path(member.name).name]=tar.extractfile(member).read()
hash=hashlib.sha256()
for name,data in sorted(files.items()):
    hash.update(name.encode()); hash.update(b'\0'); hash.update(data)
expected=hash.hexdigest()
try:
    install(a.release_apk)
    install(a.test_apk)
    output=run(prefix+['shell','am','instrument','-w','-e','expectedStartupStoreSha256',expected,
                        package+'.test/'+package+'.LocalMvpInstrumentation'])
    (a.directory/'instrumentation.txt').write_bytes(output)
    assert b'PASS: Payload Application' in output,output.decode(errors='replace')
    print('PASS disabled Release: Application/Activity/JNI/SPI/coroutines/schema; startup store SHA256 unchanged '+expected,flush=True)
finally:
    install(a.restore_debug_apk)
    run(prefix+['shell','am','start','-n',package+'/.MainActivity'])
