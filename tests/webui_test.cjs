const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const root=require('node:path').resolve(__dirname,'..'),html=fs.readFileSync(root+'/module/webroot/index.html','utf8');
function element(tag=''){return {value:'',checked:false,hidden:/\bhidden\b/.test(tag),disabled:false,textContent:'',options:[],dataset:{},attributes:{},addEventListener(type,fn){this[type]=fn;},setAttribute(k,v){this.attributes[k]=v;},removeAttribute(k){delete this.attributes[k];},focus(){this.focused=true;},replaceChildren(){this.options=[];this.value='';},append(o){this.options.push(o);if(!this.value)this.value=o.value;}};}
const elements=Object.fromEntries([...html.matchAll(/<[^>]*\bid="([^"]+)"[^>]*>/g)].map(m=>[m[1],element(m[0])]));
const nav=['overview','snapshots','settings'].map(view=>Object.assign(element(),{dataset:{view}}));
const calls=[],jobs=[],timers=new Map(),events={};let timerId=0,rejectPlan=false,holdStatus=false,held=[],busy=false,config={externalOrigin:'https://nas.example.com',internalOrigin:'',path:'/backup/',wifiNames:[],username:'u',auto:true,passwordSaved:true,toolVersion:'0.1.0-beta.6'};
const sandbox={console,TextEncoder,Date,JSON,Promise,Error,Uint8Array,URL,btoa:s=>Buffer.from(s,'binary').toString('base64'),setTimeout(fn,ms){timers.set(++timerId,{fn,ms});return timerId;},clearTimeout(id){timers.delete(id);},confirm:()=>true,scrollTo(){},document:{hidden:false,getElementById:id=>elements[id],createElement:()=>element(),querySelectorAll:()=>nav,addEventListener:(event,fn)=>events[event]=fn}};
sandbox.window=sandbox;sandbox.ksu={exec(command,options,cb){calls.push(command);let result;
 if(command.includes(' > /data/adb/')){jobs.push(command);queueMicrotask(()=>sandbox[cb](0,'',''));return;}
 if(command.endsWith(' status'))result={ok:true,busy,config,status:{phase:'等待备份'},restore:{phase:'none'},lastBackup:{},connection:{mode:'external',peerPending:true}};
 else if(command.includes(' configure ')){const payload=JSON.parse(Buffer.from(command.match(/printf %s '([^']+)'/)[1],'base64').toString());config={...config,...payload};result={ok:true,result:{saved:true}};}
 else if(command.endsWith(' history'))result={ok:true,result:{snapshots:[{vault:'test',id:'1761000000000-1234abcd'}]}};
 else if(command.includes(' plan '))result={ok:true,result:{roots:2,entries:8,downloadBytes:100,blockers:rejectPlan?['环境不一致']:[],warnings:['test'],digest:'digest'}};
 else if(command.endsWith(' wifi'))result={ok:true,result:{ssid:'Home, 5G'}};
 else result={ok:true,result:{connected:true}};
 const done=()=>sandbox[cb](0,JSON.stringify(result),'');if(command.endsWith(' status')&&holdStatus)held.push(done);else queueMicrotask(done);
}};
vm.createContext(sandbox);vm.runInContext(fs.readFileSync(root+'/module/webroot/app.js','utf8'),sandbox);
const tick=()=>new Promise(r=>setImmediate(r));let checks=0;function check(ok,message){assert(ok,message);checks++;}
(async()=>{
 await tick();check(elements.status.textContent==='等待备份','状态呈现');check(elements.password.value==='','不回填密码');check(elements.version.textContent==='0.1.0-beta.6','版本来自运行时');
 nav[2].onclick();check(!elements['view-settings'].hidden&&elements['view-overview'].hidden&&nav[2].attributes['aria-current']==='page','原生导航切换');
 elements.username.value='unsaved';await sandbox.refresh();check(elements.username.value==='unsaved','轮询保留未保存输入');
 holdStatus=true;const count=calls.length;const a=sandbox.refresh(),b=sandbox.refresh();check(calls.length===count+1&&a===b,'慢状态请求去重且串行');held.splice(0).forEach(fn=>fn());await a;holdStatus=false;
 elements.wifiNames.value=' leading and trailing ';elements.password.value="quote'\n$(touch /tmp/not-run) 中文";elements.settings.submit({preventDefault(){}});await tick();
 const conf=calls.find(c=>c.includes(' configure '));check(!!conf&&!conf.includes('touch'),'输入不拼接进Shell');const payload=JSON.parse(Buffer.from(conf.match(/printf %s '([^']+)'/)[1],'base64').toString());check(payload.password.includes('中文')&&payload.password.includes('\n'),'特殊字符无损传递');check(payload.wifiNames[0]===' leading and trailing ','SSID首尾空格保留精确匹配');check(elements.password.value==='','保存后清空密码');elements.wifiNames.value='';
 await elements.addWifi.onclick();await elements.addWifi.onclick();check(elements.wifiNames.value==='Home, 5G','添加实际SSID且去重');
 await elements.history.onclick();check(elements.snapshots.options.length===1,'历史快照呈现');rejectPlan=true;await elements.plan.onclick();check(elements.confirmBox.hidden,'有阻塞不显示确认');
 rejectPlan=false;await elements.plan.onclick();check(!elements.confirmBox.hidden&&elements.restore.disabled,'通过预检仍需明确确认');elements.agree.checked=true;elements.agree.onchange();check(!elements.restore.disabled,'勾选才允许恢复');
 await elements.restore.onclick();check(jobs.length===1&&jobs[0].includes(' restore stdin'),'正式恢复后台启动');check(elements.confirmBox.hidden&&elements.restore.disabled,'启动后清除恢复许可');
 // Allow the launch grace interval to expire in the test clock.
 vm.runInContext('pendingLaunch=0',sandbox);await sandbox.refresh();await elements.export.onclick();check(jobs.length===2&&jobs[1].includes(' export stdin'),'单ZIP导出后台启动');
 elements.snapshots.onchange();check(elements.confirmBox.hidden,'切换快照清除确认');
 vm.runInContext('pendingLaunch=0',sandbox);busy=true;await sandbox.refresh();check(elements.backup.disabled&&elements.saveSettings.disabled&&elements.restore.disabled,'后台忙碌禁止冲突操作');busy=false;await sandbox.refresh();
 elements.polling.onclick();check(elements.polling.attributes['aria-pressed']==='true'&&![...timers.values()].some(t=>[1500,6000].includes(t.ms)),'暂停自动更新');elements.polling.onclick();await tick();sandbox.document.hidden=true;events.visibilitychange();check(![...timers.values()].some(t=>[1500,6000].includes(t.ms)),'后台页面停止轮询');
 check(!/https:\/\/cdn|<script[^>]*src="https?:/.test(html),'无远程脚本');check(!fs.readFileSync(root+'/module/webroot/app.js','utf8').includes('innerHTML'),'不以HTML渲染不可信数据');
 console.log(JSON.stringify({passed:checks,suite:'webui',maxConcurrentStatusRequests:1}));
})().catch(e=>{console.error(e);process.exit(1);});
