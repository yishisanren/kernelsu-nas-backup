#!/usr/bin/env python3
"""Reproducible isolated Android verification; never backs up real installed app data."""
import argparse,json,pathlib,subprocess,tempfile,time,shutil,sys
from build import ROOT,BUILD
parser=argparse.ArgumentParser();parser.add_argument('--serial',required=True);args=parser.parse_args()
adb=['adb','-s',args.serial];report=ROOT/'docs/evidence';report.mkdir(parents=True,exist_ok=True)
def run(argv):
    result=subprocess.run(argv,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    if result.returncode:print(result.stdout,file=sys.stderr)
    result.check_returncode()
    return result.stdout
def phone(command):return run(adb+['shell',"su -M -W -c '"+command+"'"])
def test(name):return phone('CLASSPATH=/data/local/tmp/nasbackup-probe-20261021.jar app_process /system/bin dev.nasbackup.'+name)
run([sys.executable,str(ROOT/'scripts/build.py')]);run([sys.executable,str(ROOT/'scripts/build_fixture.py')])
temp=pathlib.Path(tempfile.mkdtemp(prefix='nasbackup-isolated-'));server=None;tls=None
try:
    server=subprocess.Popen([sys.executable,str(ROOT/'tests/dav_server.py'),'--root',str(temp),'--port','18791'],stdout=subprocess.PIPE,text=True)
    ready=server.stdout.readline();assert json.loads(ready)['ready']
    run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(temp/'key.pem'),'-out',str(temp/'cert.pem'),'-days','1','-subj','/CN=localhost'])
    tls=subprocess.Popen([sys.executable,str(ROOT/'tests/dav_server.py'),'--root',str(temp/'tls'),'--port','18794','--cert',str(temp/'cert.pem'),'--key',str(temp/'key.pem')],stdout=subprocess.PIPE,text=True)
    assert json.loads(tls.stdout.readline())['ready']
    run(adb+['reverse','tcp:18791','tcp:18791'])
    run(adb+['reverse','tcp:18794','tcp:18794'])
    for local,remote in [(BUILD/'nasbackup-test.jar','nasbackup-probe-20261021.jar'),(BUILD/'fixture/fixture.apk','nasbackup-fixture.apk'),(BUILD/'fixture/split.apk','nasbackup-fixture-split.apk')]:run(adb+['push',str(local),'/data/local/tmp/'+remote])
    integration=test('Integration http://127.0.0.1:18791/ https://127.0.0.1:18794/');(report/'android-integration.log').write_text(integration)
    fixture=test('ApkTest');(report/'apk-session.json').write_text(fixture)
    web=run(['node',str(ROOT/'tests/webui_test.cjs')]);(report/'webui.json').write_text(web)
    summary={'android':json.loads(integration.splitlines()[-1]),'apk':json.loads(fixture.splitlines()[-1]),'webui':json.loads(web),'realDataUploaded':False,'productionModulesChanged':False}
    (report/'isolated-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))
    print(json.dumps({'passed':True,'androidChecks':summary['android']['passed'],'webuiChecks':summary['webui']['passed'],'report':str(report)},ensure_ascii=False))
finally:
    subprocess.run(adb+['reverse','--remove','tcp:18791'],stdout=subprocess.DEVNULL)
    subprocess.run(adb+['reverse','--remove','tcp:18794'],stdout=subprocess.DEVNULL)
    # Only fixed paths owned by this verifier are removed.
    try:phone('rm -rf /data/local/tmp/nasbackup-integration /data/local/tmp/nasbackup-apk-test; rm -f /data/local/tmp/nasbackup-probe-20261021.jar /data/local/tmp/nasbackup-fixture.apk /data/local/tmp/nasbackup-fixture-split.apk')
    finally:
        if server is not None:server.terminate();server.wait(timeout=10)
        if tls is not None:tls.terminate();tls.wait(timeout=10)
        shutil.rmtree(temp)
