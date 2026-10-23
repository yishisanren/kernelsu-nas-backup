#!/usr/bin/env python3
"""Disposable-emulator regression and equal-workload comparison with released beta.4."""
import argparse,json,pathlib,subprocess,sys,tempfile,shutil,tarfile,io,os,statistics
from build import ROOT,BUILD

p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--report-dir',type=pathlib.Path,required=True);p.add_argument('--trials',type=int,default=3);p.add_argument('--benchmark-only',action='store_true');a=p.parse_args()
assert a.serial.startswith('emulator-') and 1<=a.trials<=5
adb=['adb','-s',a.serial]
def run(command,**kwargs):
    result=subprocess.run(command,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,**kwargs)
    if result.returncode:print(result.stdout,file=sys.stderr)
    result.check_returncode();return result.stdout
def shell(command):return run(adb+['shell',command])
assert shell('getprop ro.kernel.qemu').strip()=='1' and shell('id -u').strip()=='0'
a.report_dir.mkdir(parents=True,exist_ok=True)
temp=pathlib.Path(tempfile.mkdtemp(prefix='nasbackup-packed-validation-'));os.chmod(temp,0o700)
port='18796';server=None
def start(folder,latency):
    global server
    server=subprocess.Popen([sys.executable,str(ROOT/'tests/dav_server.py'),'--root',str(folder),'--port',port,'--latency-ms',str(latency),'--keep-alive'],stdout=subprocess.PIPE,text=True)
    assert json.loads(server.stdout.readline())['ready']
def stop():
    global server
    if server:server.terminate();server.wait(timeout=10);server=None
def java(jar,main,args=''):
    text=shell('CLASSPATH=/data/local/tmp/'+jar+' app_process /system/bin dev.nasbackup.'+main+' '+args)
    return json.loads(text.splitlines()[-1])
try:
    run(adb+['push',str(BUILD/'nasbackup-test.jar'),'/data/local/tmp/nasbackup-packed.jar'])
    # The emulator's host-loopback alias avoids routing bulk data through ADB itself.
    if a.benchmark_only:regression=json.loads((a.report_dir/'packed-regression.json').read_text());assert regression['passed']
    else:
        start(temp/'regression',0);regression=java('nasbackup-packed.jar','PackedTest','http://10.0.2.2:'+port+'/');stop()
        (a.report_dir/'packed-regression.json').write_text(json.dumps(regression,ensure_ascii=False,indent=2))
    baseline=temp/'baseline';baseline.mkdir()
    archive=subprocess.check_output(['git','archive','v0.1.0-beta.4'],cwd=ROOT)
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:tar.extractall(baseline)
    shutil.copy2(ROOT/'tests/java/dev/nasbackup/TransferBenchmark.java',baseline/'tests/java/dev/nasbackup/TransferBenchmark.java')
    run([sys.executable,str(baseline/'scripts/build.py')],cwd=baseline)
    run(adb+['push',str(baseline/'build/nasbackup-test.jar'),'/data/local/tmp/nasbackup-baseline.jar'])
    trials=[]
    for latency in [0,25]:
        for trial in range(a.trials):
            # Alternate execution order; use fresh DAV stores and restore stages every time.
            for mode in (['beta4','beta5'] if trial%2==0 else ['beta5','beta4']):
                start(temp/('dav-'+str(latency)+'-'+str(trial)+'-'+mode),latency)
                result=java('nasbackup-baseline.jar' if mode=='beta4' else 'nasbackup-packed.jar','TransferBenchmark','http://10.0.2.2:'+port+'/ benchmark')
                result.update({'mode':mode,'trial':trial+1,'addedServerLatencyMillis':latency});trials.append(result);stop()
                (a.report_dir/'speed-trials.json').write_text(json.dumps(trials,ensure_ascii=False,indent=2))
                print(json.dumps({'progress':len(trials),'total':a.trials*4,'mode':mode,'latency':latency}),flush=True)
    medians=[]
    for latency in [0,25]:
        for mode in ['beta4','beta5']:
            group=[r for r in trials if r['mode']==mode and r['addedServerLatencyMillis']==latency]
            summary={'mode':mode,'addedServerLatencyMillis':latency}
            for phase in ['baselineBackup','unchangedBackup','incrementalBackup','formalRestorePrepare']:
                summary[phase]={key:statistics.median(r[phase][key] for r in group) for key in group[0][phase]}
            medians.append(summary)
    report={'passed':True,'physicalDeviceModified':False,'environment':'Android 35 arm64 disposable emulator, host-loopback alias to Python WebDAV; synthetic data only','baselineTag':'v0.1.0-beta.4','newVersion':'0.1.0-beta.5','fixture':'640 distinct 4KiB text files, 16 distinct 64KiB random files, 40MiB random file, module.prop, symlink, FIFO','increment':'one text file plus one byte in second 8MiB chunk','trialsPerProfile':a.trials,'extraPrefetchUsed':False,'latencyMeaning':'Controlled server delay per HTTP request; 0ms and 25ms are laboratory profiles, not measured NAS or LAN latency','trials':trials,'medians':medians}
    (a.report_dir/'speed-comparison.json').write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps({'passed':True,'packedChecks':regression['passed'],'trials':len(trials)}))
finally:
    stop()
    try:shell('rm -rf /data/local/tmp/nasbackup-packed-test /data/local/tmp/nasbackup-transfer-benchmark; rm -f /data/local/tmp/nasbackup-packed.jar /data/local/tmp/nasbackup-baseline.jar')
    finally:shutil.rmtree(temp)
