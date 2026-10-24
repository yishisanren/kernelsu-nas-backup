// Native Android WebView: ADB injects touch; CDP observes the resulting DOM.
const {chromium}=require('playwright'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{execFile}=require('node:child_process'),{promisify}=require('node:util');
const exec=promisify(execFile),[serial,port,report]=process.argv.slice(2),cli='/data/adb/modules/nas_module_backup/bin/nasbackup';
assert(/^emulator-\d+$/.test(serial)&&/^\d+$/.test(port));let checks=0,keyboardDismissals=0;const errors=[],touches=[];
function check(ok,label){assert(ok,label);checks++;}
async function adb(command){return (await exec('adb',['-s',serial,'shell','export PATH=/data/local/tmp/nasbackup-ui-su:$PATH; '+command],{timeout:120000,maxBuffer:4*1024*1024})).stdout;}
async function backend(){return JSON.parse(await adb(cli+' status'));}
(async()=>{
 check((await adb('getprop ro.kernel.qemu')).trim()==='1'&&(await adb('id -u')).trim()==='0','owned root emulator');
 const browser=await chromium.connectOverCDP('http://127.0.0.1:'+port,{noDefaults:true});
 try{
  const page=browser.contexts()[0].pages()[0];page.on('pageerror',e=>errors.push(e.message));
  await page.waitForFunction(()=>window.ksu&&document.querySelector('#version').textContent==='0.1.0-beta.6'&&!document.querySelector('#backup').disabled);
  const geometry=await page.evaluate(()=>({...JSON.parse(ksu.geometry()),ratio:devicePixelRatio,userAgent:navigator.userAgent}));
  check(geometry.userAgent.includes('wv'),'actual Android WebView user agent');
  check(await page.locator('#version').textContent()==='0.1.0-beta.6','released runtime loaded');
  async function tap(selector){
   if(await page.evaluate(()=>document.activeElement.matches('input,textarea'))){
    await page.waitForTimeout(350);
    if(/mInputShown=true/.test(await adb('dumpsys input_method'))){await adb('input keyevent 4');keyboardDismissals++;await page.waitForFunction(height=>JSON.parse(ksu.geometry()).height>=height,geometry.height);}
   }
   // WebView's CDP viewport metrics differ from Chromium desktop; use its DOM and native origin.
   await page.locator(selector).evaluate(e=>e.scrollIntoView({block:'center',inline:'center',behavior:'instant'}));await settledScroll();
   const point=await page.locator(selector).evaluate(e=>{const b=e.getBoundingClientRect();return {x:b.x+b.width/2,y:b.y+b.height/2};});
   check(await page.evaluate(({selector,point})=>document.querySelector(selector).contains(document.elementFromPoint(point.x,point.y)),{selector,point}),'touch target unobscured '+selector);
   const origin=await page.evaluate(()=>({...JSON.parse(ksu.geometry()),ratio:devicePixelRatio}));
   const x=Math.round(origin.x+point.x*origin.ratio),y=Math.round(origin.y+point.y*origin.ratio);
   await adb('input touchscreen -d 0 tap '+x+' '+y);touches.push(selector);
  }
  await tap('[data-view=settings]');await page.waitForFunction(()=>!document.querySelector('#view-settings').hidden);
  check(await page.locator('#password').inputValue()==='','password stays blank');
  async function settledScroll(){let last=-1,stable=0;for(let i=0;i<50;i++){const y=await page.evaluate(()=>scrollY);stable=y===last?stable+1:0;last=y;if(stable>=4)return y;await page.waitForTimeout(100);}throw new Error('Native scroll never settled');}
  const start=await page.evaluate(()=>scrollY),x=geometry.x+geometry.width-12,from=Math.round(geometry.y+geometry.height*.78),to=Math.round(geometry.y+geometry.height*.30);
  await adb('input touchscreen -d 0 swipe '+x+' '+from+' '+x+' '+to+' 600');
  const after=await settledScroll();check(after>start+100,'native touch scroll down');
  await adb('input touchscreen -d 0 swipe '+x+' '+to+' '+x+' '+from+' 600');
  const returned=await settledScroll();
  check(returned<after-100,'native touch scroll up');
  await page.locator('#wifiNames').fill('AndroidWifi\nStudio 5G\nAndroidWifi');
  await tap('#saveSettings');await page.waitForFunction(()=>document.querySelector('#message').textContent.includes('设置已保存'));
  const saved=await backend();check(saved.config.wifiNames.length===2&&saved.config.passwordSaved,'saved through native bridge; names deduplicated; password retained');
  await page.waitForFunction(()=>!document.querySelector('#test').disabled);await tap('#test');await page.waitForFunction(()=>document.querySelector('#message').textContent.includes('检查通过'));
  check((await backend()).connection.mode==='internal','real CLI selects matching LAN');
  await tap('[data-view=overview]');
  async function backup(){
   const previous=(await backend()).lastBackup.id,start=performance.now();await tap('#backup');let state;
   for(let i=0;i<240;i++){state=await backend();if(state.lastBackup.id!==previous&&!state.busy)break;await new Promise(r=>setTimeout(r,250));}
   check(state.lastBackup.id!==previous&&!state.busy,'native backup committed');await page.waitForFunction(()=>!document.querySelector('#backup').disabled);
   return {milliseconds:Math.round(performance.now()-start),uploadedFiles:state.lastBackup.uploadedFiles,uploadedBytes:state.lastBackup.uploadedBytes};
  }
  const unchanged=await backup();check(unchanged.uploadedBytes===0,'unchanged native backup reuses all content');
  await adb("grep -qx id=nasbackup_ui_fixture /data/adb/modules/nasbackup_ui_fixture/module.prop && printf %s android-webview-touch-change > /data/adb/modules/nasbackup_ui_fixture/settings");
  const changed=await backup();check(changed.uploadedFiles===1&&changed.uploadedBytes>0,'changed native backup uploads one pack');
  await tap('[data-view=snapshots]');await tap('#history');await page.waitForFunction(()=>document.querySelector('#snapshots').options.length>=3);
  await page.waitForFunction(()=>!document.querySelector('#plan').disabled);await tap('#plan');await page.waitForFunction(()=>!document.querySelector('#planInfo').hidden);
  check(await page.locator('#restore').isDisabled(),'restore remains gated, no restore touch');check((await backend()).restore.phase==='none','no restore task written');
  for(const theme of ['light','dark']){
   await page.emulateMedia({colorScheme:theme});await tap('[data-view=settings]');
   check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'native layout no horizontal overflow '+theme);
   fs.writeFileSync(path.join(report,'android-webview-'+theme+'.png'),(await exec('adb',['-s',serial,'exec-out','screencap','-p'],{encoding:'buffer',maxBuffer:8*1024*1024})).stdout);
  }
  check(errors.length===0,'no native page errors '+errors.join(';'));
  const result={passed:checks,engine:geometry.userAgent,physicalTouchInjection:'adb input touchscreen -d 0',scroll:{start,after,returned},nativeTouchActions:touches,keyboardDismissals,backup:{unchanged,changed},errors,physicalDeviceModified:false,productionRestorePerformed:false,realKernelSUBridgeVerified:false};
  fs.rmSync(path.join(report,'android-webview-failure.png'),{force:true});
  fs.writeFileSync(path.join(report,'android-webview.json'),JSON.stringify(result,null,2));console.log(JSON.stringify({passed:checks,scroll:result.scroll,backup:result.backup}));
 }catch(error){
  const page=browser.contexts()[0]?.pages()[0];
  if(page){console.error(JSON.stringify(await page.evaluate(()=>({message:document.querySelector('#message')?.textContent,active:document.activeElement?.id,geometry:ksu.geometry(),viewport:{width:innerWidth,height:innerHeight,top:visualViewport.offsetTop},save:document.querySelector('#saveSettings')?.getBoundingClientRect().toJSON()}))));
   fs.writeFileSync(path.join(report,'android-webview-failure.png'),(await exec('adb',['-s',serial,'exec-out','screencap','-p'],{encoding:'buffer',maxBuffer:8*1024*1024})).stdout);}
  throw error;
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1});
