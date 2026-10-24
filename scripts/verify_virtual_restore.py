#!/usr/bin/env python3
"""Exercise production restore across actual reboots of a disposable emulator."""
import argparse,json,pathlib,subprocess,sys,tempfile,time,shutil
from build import ROOT,BUILD

p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--report-dir',type=pathlib.Path,required=True);p.add_argument('--cleanup-only',action='store_true');a=p.parse_args()
assert a.serial.startswith('emulator-'), 'A physical device is never accepted'
adb=['adb','-s',a.serial]
def run(args):
    try:return subprocess.check_output(args,text=True,stderr=subprocess.STDOUT)
    except subprocess.CalledProcessError as error:print(error.output,file=sys.stderr);raise
def shell(cmd):return run(adb+['shell',cmd])
assert shell('getprop ro.kernel.qemu').strip()=='1'
assert shell('id -u').strip()=='0'
a.report_dir.mkdir(parents=True,exist_ok=True)
temp=pathlib.Path(tempfile.mkdtemp(prefix='nasbackup-virtual-dav-'));server=None
def boot():
    deadline=time.monotonic()+150
    while time.monotonic()<deadline:
        try:
            run(adb+['wait-for-device']);run(adb+['root']);run(adb+['wait-for-device'])
            if shell('getprop sys.boot_completed').strip()=='1' and shell('am get-started-user-state 0').strip()=='RUNNING_UNLOCKED':return
        except subprocess.CalledProcessError:pass
        time.sleep(2)
    raise RuntimeError('Disposable emulator did not finish booting')
def phase(name):
    text=shell('CLASSPATH=/data/local/tmp/nasbackup-virtual.jar app_process /system/bin dev.nasbackup.VirtualRecovery '+name)
    result=json.loads(text.splitlines()[-1]);assert result['passed'];return result
try:
    if a.cleanup_only:sys.exit(0)
    server=subprocess.Popen([sys.executable,str(ROOT/'tests/dav_server.py'),'--root',str(temp),'--port','18795'],stdout=subprocess.PIPE,text=True)
    assert json.loads(server.stdout.readline())['ready']
    run(adb+['reverse','tcp:18795','tcp:18795'])
    run(adb+['push',str(BUILD/'nasbackup-test.jar'),'/data/local/tmp/nasbackup-virtual.jar'])
    run(adb+['push',str(BUILD/'fixture/fixture.apk'),'/data/local/tmp/nasbackup-fixture.apk'])
    prepared=phase('prepare http://127.0.0.1:18795/')
    # Both boot phases deliberately run without a NAS reverse mapping.
    run(adb+['reverse','--remove','tcp:18795'])
    run(adb+['reboot']);boot();applied=phase('apply')
    run(adb+['reboot']);boot();finished=phase('finish')
    result={'passed':True,'environment':'disposable Android emulator','api':shell('getprop ro.build.version.sdk').strip(),'prepare':prepared,'firstBoot':applied,'secondBoot':finished,'physicalDeviceModified':False,'extraPrefetchUsed':False,'frameworkStartupSimulated':True}
    (a.report_dir/'virtual-two-boot-restore.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print(json.dumps(result,ensure_ascii=False))
finally:
    try:
        owner=shell('if [ -f /data/local/tmp/nasbackup-virtual-recovery/owner ]; then cat /data/local/tmp/nasbackup-virtual-recovery/owner; fi').strip()
        if owner:
            assert owner=='nasbackup-virtual-v1' and shell('getprop ro.kernel.qemu').strip()=='1' and shell('id -u').strip()=='0'
            if 'package:' in shell('pm path dev.nasbackup.fixture'):assert 'Success' in shell('pm uninstall dev.nasbackup.fixture')
            shell('rm -rf /data/local/tmp/nasbackup-virtual-recovery /data/adb/modules/nasbackup_virtual_recovery /data/adb/nasbackup_virtual_recovery; rm -f /data/adb/lspd/config/modules_config.db /data/adb/lspd/config/modules_config.db-wal /data/adb/lspd/config/modules_config.db-shm /data/local/tmp/nasbackup-virtual.jar /data/local/tmp/nasbackup-fixture.apk')
        (a.report_dir/'virtual-cleanup.json').write_text(json.dumps({'passed':True,'ownerGuardChecked':True,'testFixtureRemoved':bool(owner),'physicalDeviceModified':False},indent=2))
    finally:
        subprocess.run(adb+['reverse','--remove','tcp:18795'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        if server is not None:server.terminate();server.wait(timeout=10)
        shutil.rmtree(temp)
