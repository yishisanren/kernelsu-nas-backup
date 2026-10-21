'use strict';
const $=id=>document.getElementById(id);
const CLI='/data/adb/modules/nas_module_backup/bin/nasbackup';
let sequence=0,restoreDigest='',loaded=false,busy=false;
const phases={'final-applying':'正在恢复 LSPosed 启用状态','rollback-pausing':'正在准备回退','rollback-final-reboot':'原应用数据已恢复，请再次重启完成回退','none':'尚无恢复任务','installing':'正在安装模块应用','awaiting-reboot':'恢复数据已就绪，请重启手机','applying':'正在应用系统配置','awaiting-unlock':'等待解锁或正在恢复应用配置','awaiting-final-reboot':'配置已恢复，请再次重启使模块生效','complete':'恢复完成；确认可用后可清理本机回退副本','prepare-failed':'恢复准备失败，可以申请回退','rollback-requested':'回退已安排，请重启手机','rollback-unlock':'等待解锁以完成回退','rolled-back':'已回退，请重启使原模块生效'};
function shell(command){return new Promise((resolve,reject)=>{if(!window.ksu||!ksu.exec){reject(new Error('请从 KernelSU 管理器打开本模块 WebUI'));return;}const name='nas_cb_'+Date.now()+'_'+sequence++;window[name]=(code,out,err)=>{delete window[name];code===0?resolve(out):reject(new Error(out||err||'执行失败'));};ksu.exec(command,'{}',name);});}
function b64(value){const bytes=new TextEncoder().encode(JSON.stringify(value));let s='';for(const b of bytes)s+=String.fromCharCode(b);return btoa(s);}
async function call(command,data){const prefix=data?'printf %s \''+b64(data)+'\' | base64 -d | ':'';const out=await shell(prefix+CLI+' '+command+(data?' stdin':''));const result=JSON.parse(out);if(!result.ok)throw new Error(result.error||'操作失败');return result.result||result;}
async function launch(command,data){const pipe=data?"printf %s '"+b64(data)+"' | base64 -d | ":'';await shell("umask 077; ("+pipe+CLI+' '+command+(data?' stdin':'')+' > /data/adb/nas_module_backup/job.json 2>&1) < /dev/null &');$('status').textContent='任务已启动，正在读取进度…';}
function lines(id){return $(id).value.split('\n').map(x=>x.trim()).filter(Boolean);}
function show(id,text){$(id).hidden=false;$(id).textContent=text;}
async function guarded(action){if(busy)return;busy=true;try{await action();}catch(e){$('message').textContent=e.message;}finally{busy=false;}}
async function refresh(){try{const data=await call('status');if(!loaded){for(const k of ['url','username'])$(k).value=data.config[k]||'';for(const k of ['auto','allowHttp'])$(k).checked=!!data.config[k];for(const k of ['zipDirs','extraPaths'])$(k).value=(data.config[k]||[]).join('\n');loaded=true;}
 $('status').textContent=data.status.phase+(data.status.error?' · '+data.status.error:'')+(data.status.phase.startsWith('读取 ')&&Number.isFinite(data.status.uploadedFiles)?' · 已上传并校验 '+(data.status.uploadedBytes/1048576).toFixed(2)+' MB':'');$('restoreState').textContent=phases[data.restore.phase]||data.restore.phase;if(data.restore.error)$('restoreState').textContent+=' · '+data.restore.error;
 const b=data.lastBackup;$('lastBackup').textContent=b.time?'最近成功：'+new Date(b.time).toLocaleString()+' · 新上传 '+(b.uploadedBytes/1048576).toFixed(2)+' MB':'';
 }catch(e){$('status').textContent=e.message;}}
$('settings').addEventListener('submit',event=>{event.preventDefault();guarded(async()=>{await call('configure',{url:$('url').value.trim(),username:$('username').value,password:$('password').value,allowHttp:$('allowHttp').checked,auto:$('auto').checked,zipDirs:lines('zipDirs'),extraPaths:lines('extraPaths')});$('password').value='';$('message').textContent='设置已保存';});});
$('test').onclick=()=>guarded(async()=>{await call('test');$('message').textContent='WebDAV 连接与目录权限检查通过';});
$('backup').onclick=()=>guarded(()=>launch('backup'));
$('inspect').onclick=()=>guarded(async()=>{const r=await call('inspect');show('inventory','KernelSU 模块：'+r.modules.length+'\nLSPosed 模块应用：'+r.packages.length+'\n配置记录：'+JSON.stringify(r.lsposed.counts)+'\n外部配置：\n'+r.externalPaths.join('\n')+'\n\n'+r.warnings.join('\n'));});
$('history').onclick=()=>guarded(async()=>{const r=await call('history');$('snapshots').replaceChildren();for(const s of r.snapshots.sort((a,b)=>b.id.localeCompare(a.id))){const option=document.createElement('option');option.value=JSON.stringify(s);option.textContent=new Date(Number(s.id.split('-')[0])).toLocaleString()+' · '+s.vault;$('snapshots').append(option);}if(!r.snapshots.length)$('message').textContent='还没有已完成的快照';});
$('snapshots').onchange=()=>{restoreDigest='';$('confirmBox').hidden=true;};
$('plan').onclick=()=>guarded(async()=>{if(!$('snapshots').value)throw new Error('请先选择快照');const r=await call('plan',JSON.parse($('snapshots').value));show('planInfo','恢复项目：'+r.roots+'\n文件记录：'+r.entries+'\n所需对象：'+(r.downloadBytes/1048576).toFixed(1)+' MB\n'+(r.blockers.length?'不能恢复：\n'+r.blockers.join('\n'):'环境检查通过')+'\n\n'+r.warnings.join('\n'));restoreDigest=r.digest;$('agree').checked=false;$('restore').disabled=true;$('confirmBox').hidden=r.blockers.length>0;});
$('agree').onchange=()=>{$('restore').disabled=!$('agree').checked;};
$('restore').onclick=()=>guarded(async()=>{if(!restoreDigest||!$('agree').checked)throw new Error('请先检查并确认恢复');await launch('restore',{digest:restoreDigest});$('confirmBox').hidden=true;});
$('rollback').onclick=()=>guarded(async()=>{if(confirm('回退本次恢复？需重启后执行，随后解锁手机完成应用数据回退。')){await call('rollback');await refresh();}});
$('clean').onclick=()=>guarded(async()=>{if(confirm('删除本机暂存和回退副本？NAS 快照会保留。')){await call('clean');$('message').textContent='已清理本机恢复暂存';}});
refresh();setInterval(refresh,5000);
