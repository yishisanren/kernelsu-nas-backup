// Requires Playwright. Talks only to the explicitly guarded disposable emulator.
const {chromium}=require('playwright'),fs=require('node:fs'),path=require('node:path'),http=require('node:http'),{execFile}=require('node:child_process'),{promisify}=require('node:util'),assert=require('node:assert/strict');
const exec=promisify(execFile),serial=process.argv[2],report=path.resolve(process.argv[3]),root=path.resolve(__dirname,'../module/webroot'),cli='/data/adb/modules/nas_module_backup/bin/nasbackup';
assert(/^emulator-\d+$/.test(serial));let checks=0;const measurements=[],errors=[];
async function adb(command){try{const r=await exec('adb',['-s',serial,'shell','export PATH=/data/local/tmp/nasbackup-ui-su:$PATH; '+command],{timeout:120000,maxBuffer:4*1024*1024});return {code:0,out:r.stdout,err:r.stderr};}catch(e){return {code:e.code||1,out:e.stdout||'',err:e.stderr||''};}}
async function backend(){const r=await adb(cli+' status');assert.equal(r.code,0);return JSON.parse(r.out);}
function check(value,label){assert(value,label);checks++;}
const server=http.createServer((req,res)=>{let name=req.url==='/'?'index.html':req.url.slice(1);if(!['index.html','style.css','app.js'].includes(name)){res.writeHead(404).end();return;}res.setHeader('Content-Type',name.endsWith('.css')?'text/css':name.endsWith('.js')?'text/javascript':'text/html');res.end(fs.readFileSync(path.join(root,name)));});
(async()=>{
 check((await adb('getprop ro.kernel.qemu')).out.trim()==='1'&&(await adb('id -u')).out.trim()==='0','guard');
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({headless:true});
 try{
 const page=await browser.newPage({viewport:{width:375,height:812},colorScheme:'light',reducedMotion:'reduce'});page.on('pageerror',e=>errors.push(e.message));
 await page.exposeFunction('fixtureExec',async command=>{assert(command.includes(cli));return adb(command);});
 await page.addInitScript(()=>{window.ksu={exec(command,options,cb){window.fixtureExec(command).then(r=>window[cb](r.code,r.out,r.err));}};});
 await page.goto('http://127.0.0.1:'+server.address().port);await page.waitForFunction(()=>!document.querySelector('#backup').disabled);
 check(await page.locator('#version').textContent()==='0.1.0-beta.6','runtime version');
 await page.locator('[data-view=settings]').click();await page.locator('#wifiNames').fill('AndroidWifi\nHome 5G');await page.locator('#saveSettings').click();await page.waitForFunction(()=>document.querySelector('#message').textContent.includes('设置已保存'));
 check((await backend()).config.wifiNames.length===2,'browser configure real CLI');
 await page.locator('#test').click();await page.waitForFunction(()=>document.querySelector('#message').textContent.includes('检查通过'));
 check((await backend()).connection.mode==='internal','browser actual SSID LAN route');
 await page.locator('[data-view=overview]').click();
 async function backup(){const old=(await backend()).lastBackup.id;const started=performance.now();await page.locator('#backup').click();let s;for(let i=0;i<120;i++){s=await backend();if(s.lastBackup.id&&s.lastBackup.id!==old&&!s.busy)break;await new Promise(r=>setTimeout(r,250));}check(s.lastBackup.id!==old&&!s.busy,'backup completed');await page.waitForFunction(()=>!document.querySelector('#backup').disabled);return {millis:performance.now()-started,uploadedBytes:s.lastBackup.uploadedBytes,uploadedFiles:s.lastBackup.uploadedFiles};}
 const first=await backup(),unchanged=await backup();check(first.uploadedBytes>0&&unchanged.uploadedBytes===0,'browser incremental reuse');
 await adb("printf '%s' changed > /data/adb/modules/nasbackup_ui_fixture/settings");const changed=await backup();check(changed.uploadedFiles===1,'browser changed only one pack');
 await page.locator('[data-view=snapshots]').click();await page.locator('#history').click();await page.waitForFunction(()=>document.querySelector('#snapshots').options.length>=3);await page.locator('#plan').click();await page.waitForFunction(()=>!document.querySelector('#planInfo').hidden);
 check(await page.locator('#restore').isDisabled(),'restore explicit consent gate');
 for(const theme of ['light','dark']){
   await page.emulateMedia({colorScheme:theme,reducedMotion:'reduce'});
   for(const width of [320,375,414,768]){await page.setViewportSize({width,height:900});
     for(const view of ['overview','snapshots','settings']){
       await page.locator('[data-view='+view+']').click();
       const result=await page.evaluate(()=>{
         const elements=[...document.querySelectorAll('main *,header *,nav *')].filter(e=>e.getClientRects().length),vw=innerWidth;
         const outside=elements.filter(e=>{const r=e.getBoundingClientRect();return r.left<-.5||r.right>vw+.5;}).map(e=>e.id||e.tagName);
         const lin=c=>c<=.04045?c/12.92:Math.pow((c+.055)/1.055,2.4),lum=c=>c.slice(0,3).map(x=>lin(x/255)).reduce((s,x,i)=>s+x*[.2126,.7152,.0722][i],0),rgb=s=>s.match(/[\d.]+/g).map(Number),ratio=(a,b)=>(Math.max(lum(a),lum(b))+.05)/(Math.min(lum(a),lum(b))+.05);
         function bg(e){for(;e;e=e.parentElement){const c=rgb(getComputedStyle(e).backgroundColor);if(c.length<4||c[3]===1)return c;}return [255,255,255];}
         const pairs=elements.filter(e=>[...e.childNodes].some(n=>n.nodeType===3&&n.textContent.trim())&&!e.closest('button:disabled')).map(e=>({id:e.id||e.tagName,ratio:ratio(rgb(getComputedStyle(e).color),bg(e))}));
         const borders=[...document.querySelectorAll('input:not([type=checkbox]),textarea,select')].filter(e=>e.getClientRects().length).map(e=>({id:e.id,ratio:ratio(rgb(getComputedStyle(e).borderColor),bg(e))}));
         return {outside,text:pairs.filter(p=>p.ratio<4.5),borders:borders.filter(p=>p.ratio<3),minText:Math.min(...pairs.map(p=>p.ratio)),minControl:borders.length?Math.min(...borders.map(p=>p.ratio)):null};
       });check(result.outside.length===0,'viewport '+theme+' '+width+' '+view+' '+JSON.stringify(result.outside));check(result.text.length===0,'text contrast '+JSON.stringify(result.text));check(result.borders.length===0,'input boundary contrast '+JSON.stringify(result.borders));measurements.push({theme,width,view,...result});
       if(width===375)await page.screenshot({path:path.join(report,'webui-'+theme+'-'+view+'.png')});
     }
   }
 }
 await page.setViewportSize({width:320,height:900});await page.evaluate(()=>document.styleSheets[0].insertRule('html{font-size:175%!important}',document.styleSheets[0].cssRules.length));
 for(const view of ['overview','snapshots','settings']){await page.locator('[data-view='+view+']').click();check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth&&![...document.querySelectorAll('main *,header *,nav *')].filter(e=>e.getClientRects().length).some(e=>{const r=e.getBoundingClientRect();return r.left<-.5||r.right>innerWidth+.5;})),'200 percent text zoom '+view);}
 // Real keyboard navigation and focus presentation, not a static attribute-only check.
 await page.reload();await page.waitForFunction(()=>!document.querySelector('#backup').disabled);await page.keyboard.press('Tab');check(await page.locator('.skip').evaluate(e=>e===document.activeElement),'keyboard skip first');await page.keyboard.press('Enter');check(await page.locator('#content').evaluate(e=>e===document.activeElement),'skip targets main');
 await page.locator('[data-view=settings]').focus();await page.keyboard.press('Enter');check(await page.locator('#title-settings').evaluate(e=>e===document.activeElement),'keyboard view focus');
 check(await page.locator('#externalOrigin').evaluate(e=>e.labels.length>0),'field native label');check(await page.locator('#password').inputValue()==='','browser password never refilled');
 await page.locator('#externalOrigin').focus();check(await page.locator('#externalOrigin').evaluate(e=>getComputedStyle(e).outlineStyle!=='none'),'visible focus');
 await page.emulateMedia({forcedColors:'active'});check(await page.locator('#externalOrigin').evaluate(e=>getComputedStyle(e).borderTopWidth==='1px'),'forced colors boundary');
 check(errors.length===0,'no page errors '+errors.join(';'));
 fs.writeFileSync(path.join(report,'webui-browser.json'),JSON.stringify({passed:checks,engine:'Playwright Chromium',backend:'actual root CLI in disposable Android35 emulator',backup:{first,unchanged,changed},measurements,errors,physicalDeviceModified:false,realKernelSUWebViewVerified:false,screenReaderVerified:false},null,2));console.log(JSON.stringify({passed:checks,first,unchanged,changed}));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;}).finally(()=>server.close());
