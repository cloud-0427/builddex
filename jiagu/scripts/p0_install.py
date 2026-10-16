"""Install an authorized test APK, handling the device's per-install confirmation UI.

Does not change security settings or submit the APK for an external scan.
"""
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('apk')
args = parser.parse_args()
prefix = ['adb', '-s', args.serial]
process = subprocess.Popen(prefix + ['install', '--no-streaming', '-r', '-t', args.apk],
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
deadline = time.monotonic() + 150
while process.poll() is None and time.monotonic() < deadline:
    time.sleep(1)
    try:
        subprocess.run(prefix + ['shell', 'uiautomator', 'dump', '/data/local/tmp/p0-install-ui.xml'],
                       capture_output=True, timeout=8)
        data = subprocess.check_output(prefix + ['exec-out', 'cat', '/data/local/tmp/p0-install-ui.xml'], timeout=5)
        root = ET.fromstring(data)
        labels = [node.get('text', '') for node in root.iter('node')]
        packages = {node.get('package', '') for node in root.iter('node')}
        if not any('packageinstaller' in p or 'appdetail' in p or p == 'com.google.android.gms' for p in packages):
            continue
        # These exact controls belong to the observed OPPO and Play Protect install flows.
        # No generic Allow/OK clicks, and no Settings changes.
        allowed = ['继续安装', '不扫描，直接安装', '更多详情', '安装']
        for text in allowed:
            node = next((n for n in root.iter('node') if n.get('text') == text and n.get('enabled') == 'true'), None)
            if node is not None:
                bounds = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
                if len(bounds) == 4:
                    x1, y1, x2, y2 = bounds
                    subprocess.run(prefix + ['shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2)], check=True)
                    print('Install confirmation:', text, flush=True)
                    break
    except (subprocess.SubprocessError, ET.ParseError):
        pass
if process.poll() is None:
    process.terminate()
    raise SystemExit('Install timed out; no unknown UI was clicked')
output = process.communicate()[0].decode(errors='replace')
print(output, flush=True)
raise SystemExit(process.returncode)
