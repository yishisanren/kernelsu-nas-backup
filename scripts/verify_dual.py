#!/usr/bin/env python3
"""Disposable emulator: aliases, error classification, repository identity and incremental reuse."""
import argparse,json,pathlib,subprocess,tempfile,shutil,sys,os
from build import ROOT,BUILD
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--report-dir',type=pathlib.Path,required=True);a=p.parse_args()
assert a.serial.startswith('emulator-')
adb=['adb','-s',a.serial]
def run(argv):return subprocess.check_output(argv,text=True,stderr=subprocess.STDOUT)
def shell(command):return run(adb+['shell',command])
assert shell('getprop ro.kernel.qemu').strip()=='1' and shell('id -u').strip()=='0'
temp=pathlib.Path(tempfile.mkdtemp(prefix='nasbackup-dual-'));os.chmod(temp,0o700);servers=[]
try:
    run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(temp/'key'),'-out',str(temp/'cert'),'-days','1','-subj','/CN=localhost'])
    for index in range(8):
        folder=temp/('shared' if index<2 else str(index));(folder/'备份 % 目录').mkdir(parents=True,exist_ok=True)
        options=[]
        if 3<=index<=6:options=['--deny',str([401,403,503,307][index-3])]
        if index==7:options=['--cert',str(temp/'cert'),'--key',str(temp/'key')]
        proc=subprocess.Popen([sys.executable,str(ROOT/'tests/dav_server.py'),'--root',str(folder),'--port',str(18801+index),'--keep-alive',*options],stdout=subprocess.PIPE,text=True);servers.append(proc)
        assert json.loads(proc.stdout.readline())['ready']
    run(adb+['push',str(BUILD/'nasbackup-test.jar'),'/data/local/tmp/nasbackup-dual.jar'])
    urls=[('https' if i==7 else 'http')+'://10.0.2.2:'+str(18801+i) for i in range(9)]
    result=shell('CLASSPATH=/data/local/tmp/nasbackup-dual.jar app_process /system/bin dev.nasbackup.ConnectionTest '+' '.join(urls))
    a.report_dir.mkdir(parents=True,exist_ok=True);(a.report_dir/'dual-endpoint.log').write_text(result)
    report=json.loads(result.splitlines()[-1]);(a.report_dir/'dual-endpoint.json').write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps({'passed':report['passed'],'physicalDeviceModified':False}))
finally:
    shell('rm -rf /data/local/tmp/nasbackup-dual-test; rm -f /data/local/tmp/nasbackup-dual.jar')
    for proc in servers:proc.terminate();proc.wait(timeout=10)
    shutil.rmtree(temp)
