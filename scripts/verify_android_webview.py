"""Native WebView + real CLI acceptance in an explicitly guarded disposable emulator."""
import argparse,http.server,json,pathlib,re,secrets,subprocess,sys,threading,time
from build import ROOT,BUILD
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--report-dir',required=True,type=pathlib.Path);a=p.parse_args()
assert re.fullmatch(r'emulator-\d+',a.serial),'Physical devices are rejected'
adb=['adb','-s',a.serial];package='dev.nasbackup.webviewfixture';cli='/data/adb/modules/nas_module_backup/bin/nasbackup'
def shell(command):return subprocess.check_output(adb+['shell',command],text=True,timeout=120)
assert shell('getprop ro.kernel.qemu').strip()=='1' and shell('id -u').strip()=='0'
assert json.loads(shell('cat /data/adb/nas_module_backup/config.json'))['vault']=='ui-fixture','Parent UI verifier must own the fixture'
assert 'package:' not in shell('pm path '+package+' 2>/dev/null || true'),'Refusing to replace an existing harness'
a.report_dir.mkdir(parents=True,exist_ok=True);token=secrets.token_hex(16);stats={'requests':0,'activeStatus':0,'maxConcurrentStatus':0};lock=threading.Lock()
class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def do_GET(self):
        name=self.path.removeprefix('/'+token+'/')
        if not self.path.startswith('/'+token+'/') or name not in ['index.html','style.css','app.js']:self.send_error(404);return
        data=(ROOT/'module/webroot'/name).read_bytes();self.send_response(200);self.send_header('Content-Type','text/css' if name.endswith('.css') else 'text/javascript' if name.endswith('.js') else 'text/html');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
    def do_POST(self):
        if self.path!='/'+token+'/exec':self.send_error(404);return
        size=int(self.headers.get('Content-Length','0'))
        if not 0<size<160000:self.send_error(413);return
        command=json.loads(self.rfile.read(size))['command'];operations=re.findall(re.escape(cli)+r' ([a-z-]+)',command)
        if len(operations)!=1 or operations[0] not in ['status','configure','wifi','test','backup','inspect','history','plan']:self.send_error(403);return
        is_status=operations[0]=='status'
        with lock:
            stats['requests']+=1
            if is_status:stats['activeStatus']+=1;stats['maxConcurrentStatus']=max(stats['maxConcurrentStatus'],stats['activeStatus'])
        try:
            r=subprocess.run(adb+['shell','export PATH=/data/local/tmp/nasbackup-ui-su:$PATH; '+command],text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=100)
            data=json.dumps({'code':r.returncode,'out':r.stdout,'err':r.stderr}).encode();self.send_response(200);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
        finally:
            if is_status:
                with lock:stats['activeStatus']-=1
server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start();port=None;installed=False
try:
    subprocess.run([sys.executable,str(ROOT/'scripts/build_webview_fixture.py')],check=True)
    assert 'Success' in subprocess.check_output(adb+['install',str(BUILD/'webview-fixture/webview.apk')],text=True);installed=True
    origin='http://10.0.2.2:'+str(server.server_port)+'/'+token+'/'
    shell('am start -W -n '+package+'/.WebActivity --es origin '+origin)
    pid=''
    for _ in range(30):
        pid=shell('pidof '+package+' || true').strip()
        if pid and 'webview_devtools_remote_'+pid in shell('cat /proc/net/unix'):break
        time.sleep(.2)
    assert pid.isdigit(),'Native WebView did not start'
    port=subprocess.check_output(adb+['forward','tcp:0','localabstract:webview_devtools_remote_'+pid],text=True).strip();assert port.isdigit()
    subprocess.run(['node',str(ROOT/'tests/android_webview.cjs'),a.serial,port,str(a.report_dir)],check=True)
    assert stats['maxConcurrentStatus']==1
    (a.report_dir/'android-webview-transport.json').write_text(json.dumps({'passed':True,**stats,'physicalDeviceModified':False,'transport':'native JavascriptInterface to nonce-scoped loopback server; actual emulator root CLI','realKernelSUBridgeVerified':False},indent=2))
finally:
    if port:subprocess.run(adb+['forward','--remove','tcp:'+port],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    if installed:shell('am force-stop '+package);assert 'Success' in shell('pm uninstall '+package)
    server.shutdown();server.server_close();thread.join(timeout=3)
