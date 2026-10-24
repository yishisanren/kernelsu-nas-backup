#!/usr/bin/env python3
"""Actual CLI plus browser, warm status timings and automatic scheduling on an owned emulator."""
import argparse,pathlib,subprocess,json,tempfile,shutil,zipfile,os,time,statistics,base64,sys,hashlib,shlex
from build import ROOT,BUILD,DIST,VERSION
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--report-dir',type=pathlib.Path,required=True);p.add_argument('--skip-auto',action='store_true');a=p.parse_args();assert a.serial.startswith('emulator-')
adb=['adb','-s',a.serial];state='/data/adb/nas_module_backup';module='/data/adb/modules/nas_module_backup';fixture='/data/adb/modules/nasbackup_ui_fixture';cli=module+'/bin/nasbackup'
def run(argv):
    try:return subprocess.check_output(argv,text=True,stderr=subprocess.STDOUT,timeout=120)
    except subprocess.CalledProcessError as e:print(e.output);raise
def shell(cmd):return run(adb+['shell','export PATH=/data/local/tmp/nasbackup-ui-su:$PATH; '+cmd])
def write(remote,data):
    value=data if isinstance(data,str) else json.dumps(data,ensure_ascii=False)
    shell("printf %s '"+base64.b64encode(value.encode()).decode()+"' | base64 -d > "+remote)
def status():return json.loads(shell(cli+' status'))
assert shell('getprop ro.kernel.qemu').strip()=='1' and shell('id -u').strip()=='0'
assert shell('test ! -e '+state+' && test ! -e '+module+' && test ! -e '+fixture+' && echo safe').strip()=='safe'
temp=pathlib.Path(tempfile.mkdtemp(prefix='nasbackup-ui-'));os.chmod(temp,0o700);servers=[];daemon='';a.report_dir.mkdir(parents=True,exist_ok=True)
try:
    shell('mkdir -p /data/local/tmp/nasbackup-ui-su')
    write('/data/local/tmp/nasbackup-ui-su/su','#!/system/bin/sh\n[ "$1" = -v ] || exit 1\necho nasbackup-emulated-ksu\n');shell('chmod 755 /data/local/tmp/nasbackup-ui-su/su')
    with zipfile.ZipFile(DIST/('nas-module-backup-'+VERSION+'.zip')) as z:z.extractall(temp/'module')
    run(adb+['push',str(temp/'module'),module]);shell('chmod 755 '+cli+'; mkdir -p '+state+' '+fixture)
    write(fixture+'/module.prop','id=nasbackup_ui_fixture\nversion=1\nversionCode=1\n');write(fixture+'/settings','initial')
    (temp/'dav/backup').mkdir(parents=True)
    for port in [18821,18822]:
        proc=subprocess.Popen([sys.executable,str(ROOT/'tests/dav_server.py'),'--root',str(temp/'dav'),'--port',str(port),'--keep-alive'],stdout=subprocess.PIPE,text=True);servers.append(proc);assert json.loads(proc.stdout.readline())['ready']
    # Existing beta.5 single-URL config remains byte-for-byte intact until an explicit save.
    old={'url':'http://10.0.2.2:18821/backup/','username':'test','password':'fixture-secret','allowHttp':True,'auto':False,'vault':'ui-fixture','zipDirs':[],'extraPaths':[]};write(state+'/config.json',old);raw=shell('sha256sum '+state+'/config.json').split()[0];s=status();assert s['config']['path']=='/backup/' and s['config']['passwordSaved'] and 'password' not in s['config'];assert shell('sha256sum '+state+'/config.json').split()[0]==raw
    baseline=DIST/'nas-module-backup-0.1.0-beta.5.zip';assert baseline.is_file(),'Keep the released beta.5 ZIP in dist for comparison'
    with zipfile.ZipFile(baseline) as z:z.extractall(temp/'baseline')
    run(adb+['push',str(temp/'baseline'),'/data/local/tmp/nasbackup-ui-baseline']);oldcli='/data/local/tmp/nasbackup-ui-baseline/bin/nasbackup';shell('chmod 755 '+oldcli)
    timings={'beta5':[],'beta6':[]}
    for command in [oldcli,cli]:shell(command+' status')
    for i in range(15):
        for name,command in ([('beta5',oldcli),('beta6',cli)] if i%2==0 else [('beta6',cli),('beta5',oldcli)]):
            start=time.perf_counter();r=json.loads(shell(command+' status'));timings[name].append((time.perf_counter()-start)*1000);assert r['ok']
    # Cache invalidates on config and restore journal changes; private fields never escape.
    old['username']='updated';write(state+'/config.json',old);assert status()['config']['username']=='updated';old['username']='test';old['password']='test';old['externalOrigin']='http://10.0.2.2:18821';old['internalOrigin']='http://10.0.2.2:18822';old['path']='/backup/';old.pop('url');write(state+'/config.json',old)
    write(state+'/operation.pid','1');assert status()['busy'];shell('rm '+state+'/operation.pid')
    write(state+'/restore.json',{'phase':'awaiting-unlock','privatePayload':'never-expose'});assert status()['restore']=={'phase':'awaiting-unlock','error':''};time.sleep(.02);write(state+'/restore.json',{'phase':'complete','privatePayload':'never-expose'});assert status()['restore']=={'phase':'complete','error':''};shell('rm '+state+'/restore.json');assert status()['restore']['phase']=='none'
    perf={'environment':'Disposable Android35 arm64; wall time includes identical adb round trip; 15 alternating warm samples','millis':timings,'medianMillis':{k:statistics.median(v) for k,v in timings.items()},'passwordReturned':False,'legacyConfigPreservedUntilSave':True,'configAndJournalCacheInvalidation':True,'busyPidDetected':True};(a.report_dir/'status-performance.json').write_text(json.dumps(perf,indent=2))
    print(run(['node',str(ROOT/'tests/webui_browser.cjs'),a.serial,str(a.report_dir)]),flush=True)
    folder=temp/'dav/backup';manifest_path=folder/('vaults/ui-fixture/snapshots/'+status()['lastBackup']['id']+'.json');payload=manifest_path.read_bytes();assert hashlib.sha256(payload).hexdigest()==manifest_path.with_suffix('.sha256').read_text().strip();m=json.loads(payload);blobs={}
    for digest,pack in m['packs'].items():
        archive=folder/('packs/'+digest+'.zip');data=archive.read_bytes();assert len(data)==pack['size'] and hashlib.sha256(data).hexdigest()==digest
        with zipfile.ZipFile(archive) as z:
            assert set(z.namelist())==set(pack['members'])
            for name,size in pack['members'].items():
                value=z.read(name);assert len(value)==size and hashlib.sha256(value).hexdigest()==name;blobs[name]=value
    roots={r['key']:r for r in m['roots']};files=0
    for entry in m['entries']:
        target=roots[entry['root']]['destination']+('/'+entry['path'] if entry['path'] else '');quoted=shlex.quote(target)
        assert target==fixture or target.startswith(fixture+'/')
        mode,uid,gid=shell("stat -c '%a %u %g' "+quoted).split();assert [int(mode,8),int(uid),int(gid)]==[entry['mode'],entry['uid'],entry['gid']]
        if entry['type']=='file':
            contents=b''.join(blobs[c['hash']] for c in entry.get('chunks',[{'hash':entry['hash']}]))
            assert len(contents)==entry['size'] and hashlib.sha256(contents).hexdigest()==entry['hash']==shell('sha256sum '+quoted).split()[0];files+=1
        else:assert entry['type']=='dir' and shell('test -d '+quoted+' && echo yes').strip()=='yes'
    (a.report_dir/'nas-source-comparison.json').write_text(json.dumps({'passed':True,'entries':len(m['entries']),'files':files,'packs':len(m['packs']),'packAndMemberSha256Verified':True,'sourceContentAndModeUidGidMatched':True,'physicalDeviceModified':False},indent=2))
    if a.skip_auto:sys.exit(0)
    # Exercise the actual production daemon: install, update, and unchanged-signature daily due.
    config=json.loads(shell('cat '+state+'/config.json'));config['auto']=True;write(state+'/config.json',config)
    daemon=shell('('+cli+' daemon > /data/local/tmp/nasbackup-ui-daemon.log 2>&1 < /dev/null & echo $!)').strip();assert daemon.isdigit();print('automatic: daemon started',flush=True)
    def success_after(previous,label):
        start=time.monotonic()
        while time.monotonic()-start<180:
            s=status()
            if s['lastBackup'].get('id')!=previous and not s['busy']:
                schedule=json.loads(shell('cat '+state+'/schedule.json'));result={'trigger':label,'observedMillis':round((time.monotonic()-start)*1000),'snapshot':s['lastBackup']['id'],'uploadedBytes':s['lastBackup']['uploadedBytes'],'signature':schedule['signature'],'success':schedule['success']};print(json.dumps(result),flush=True);return result
            time.sleep(2)
        raise RuntimeError('No automatic backup: '+label)
    before=status()['lastBackup']['id'];installed=success_after(before,'first daemon observation')
    write(fixture+'/module.prop','id=nasbackup_ui_fixture\nversion=2\nversionCode=2\n');updated=success_after(installed['snapshot'],'module version update');assert updated['uploadedBytes']>0
    schedule=json.loads(shell('cat '+state+'/schedule.json'));schedule['success']=int(time.time()*1000)-86401000;write(state+'/schedule.json',schedule);daily=success_after(updated['snapshot'],'daily due unchanged signature');assert daily['signature']==updated['signature'] and daily['uploadedBytes']==0
    (a.report_dir/'automatic-backup.json').write_text(json.dumps({'passed':True,'install':installed,'update':updated,'daily':daily,'systemClockChanged':False,'physicalDeviceModified':False},indent=2));print(json.dumps({'passed':True,'statusMedianMillis':perf['medianMillis'],'automatic':'install/update/daily passed'}))
finally:
    if daemon:shell('kill '+daemon)
    shell('rm -rf '+state+' '+module+' '+fixture+' /data/local/tmp/nasbackup-ui-baseline /data/local/tmp/nasbackup-ui-su; rm -f /data/local/tmp/nasbackup-ui-daemon.log')
    for proc in servers:proc.terminate();proc.wait(timeout=10)
    shutil.rmtree(temp)
