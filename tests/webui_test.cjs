const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const root=require('node:path').resolve(__dirname,'..');
const ids=[...fs.readFileSync(root+'/module/webroot/index.html','utf8').matchAll(/id="([^"]+)"/g)].map(m=>m[1]);
const elements=Object.fromEntries(ids.map(id=>[id,{value:'',checked:false,hidden:false,disabled:false,textContent:'',options:[],addEventListener(type,fn){this[type]=fn;},replaceChildren(){this.options=[];this.value='';},append(o){this.options.push(o);if(!this.value)this.value=o.value;}}]));
const calls=[],jobs=[];let rejectPlan=false;
const sandbox={console,TextEncoder,Date,JSON,Promise,Error,Uint8Array,btoa:s=>Buffer.from(s,'binary').toString('base64'),setInterval(){},confirm:()=>true,document:{getElementById:id=>elements[id],createElement:()=>({})}};
sandbox.window=sandbox;sandbox.ksu={exec(command,options,cb){calls.push(command);let result;
if(command.includes(' > /data/adb/')){jobs.push(command);sandbox[cb](0,'','');return;}
if(command.endsWith(' status'))result={ok:true,config:{url:'https://nas.test/',username:'u',auto:true,passwordSaved:true},status:{phase:'等待备份'},restore:{phase:'none'},lastBackup:{}};
else if(command.includes('configure'))result={ok:true,result:{saved:true}};
else if(command.endsWith(' history'))result={ok:true,result:{snapshots:[{vault:'test',id:'1761000000000-1234abcd'}]}};
else if(command.includes(' plan '))result={ok:true,result:{roots:2,entries:8,downloadBytes:100,blockers:rejectPlan?['环境不一致']:[],warnings:['test'],digest:'digest'}};
else result={ok:true,result:{connected:true}};
queueMicrotask(()=>sandbox[cb](0,JSON.stringify(result),''));}};
vm.createContext(sandbox);vm.runInContext(fs.readFileSync(root+'/module/webroot/app.js','utf8'),sandbox);
const tick=()=>new Promise(r=>setImmediate(r));let checks=0;function check(ok,message){assert(ok,message);checks++;}
(async()=>{
 await tick();check(elements.status.textContent==='等待备份','状态呈现');check(elements.password.value==='','不回填密码');
 elements.password.value="quote'\n$(touch /tmp/not-run) 中文";await elements.settings.submit({preventDefault(){}});await tick();
 const conf=calls.find(c=>c.includes(' configure '));check(!conf.includes('touch'),'用户输入不直接拼成Shell');
 const payload=JSON.parse(Buffer.from(conf.match(/printf %s '([^']+)'/)[1],'base64').toString());check(payload.password.includes('中文')&&payload.password.includes('\n'),'中文及特殊字符无损传递');check(elements.password.value==='','保存后清空密码字段');
 await elements.history.onclick();await tick();check(elements.snapshots.options.length===1,'历史快照呈现');
 rejectPlan=true;await elements.plan.onclick();check(elements.confirmBox.hidden,'条件不满足不显示恢复确认');
 rejectPlan=false;await elements.plan.onclick();check(!elements.confirmBox.hidden&&elements.restore.disabled,'预检通过后仍需显式勾选');
 elements.agree.checked=true;elements.agree.onchange();check(!elements.restore.disabled,'确认后允许恢复');
 await elements.restore.onclick();check(jobs.length===1&&jobs[0].includes(' restore stdin'),'恢复异步启动');
 elements.snapshots.onchange();check(elements.confirmBox.hidden,'切换快照清除确认');
 check(!fs.readFileSync(root+'/module/webroot/index.html','utf8').includes('https://cdn'),'无外部脚本依赖');
 console.log(JSON.stringify({passed:checks,suite:'webui'}));
})().catch(e=>{console.error(e);process.exit(1);});
