'use strict';
const $=id=>document.getElementById(id);
const CLI='/data/adb/modules/nas_module_backup/bin/nasbackup';
const phases={'final-applying':'正在恢复 LSPosed 启用状态','rollback-pausing':'正在准备回退','rollback-final-reboot':'应用数据已回退，请再次重启','none':'暂无恢复任务','installing':'正在安装模块应用','awaiting-reboot':'恢复数据已就绪，请重启','applying':'正在恢复系统配置','awaiting-unlock':'等待解锁以恢复应用配置','awaiting-final-reboot':'应用配置已恢复，请再次重启','complete':'恢复已完成，可核验后清理暂存','prepare-failed':'恢复准备失败，可申请回退','rollback-requested':'回退已安排，请重启','rollback-unlock':'等待解锁以完成回退','rolled-back':'回退已完成，请重启使原模块生效'};
let sequence=0,loaded=false,actionRunning=false,refreshPromise=null,pollTimer=0,paused=false,lastBusy=false,pendingLaunch=0,restoreDigest='',restorePhase='none',configured=false,lastBackendError='';
function text(id,value){const e=$(id),s=String(value);if(e.textContent!==s)e.textContent=s;}
function notify(value,error=false,source='action'){const e=$('message');e.hidden=!value;e.dataset.error=String(error);e.dataset.source=source;e.setAttribute('role',error?'alert':'status');text('message',value);}
function size(value){if(!Number.isFinite(value))return '—';if(value<1024)return value+' B';const units=['KiB','MiB','GiB','TiB'];let n=value/1024,i=0;while(n>=1024&&i<units.length-1){n/=1024;i++;}return n.toLocaleString('zh-CN',{maximumFractionDigits:2})+' '+units[i];}
function date(value){return value?new Date(value).toLocaleString('zh-CN',{hour12:false}):'—';}
function shell(command){return new Promise((resolve,reject)=>{
  if(!window.ksu||!ksu.exec){reject(new Error('请从 KernelSU 管理器打开本模块 WebUI'));return;}
  const name='nas_cb_'+Date.now()+'_'+sequence++;
  const timer=setTimeout(()=>{delete window[name];reject(new Error('操作尚未返回，请稍后查看任务状态'));},command.endsWith(' status')?15000:90000);
  window[name]=(code,out,err)=>{clearTimeout(timer);delete window[name];code===0?resolve(out):reject(new Error(out||err||'执行失败，请重试'));};
  try{ksu.exec(command,'{}',name);}catch(e){clearTimeout(timer);delete window[name];reject(e);}
});}
function b64(value){const bytes=new TextEncoder().encode(JSON.stringify(value));let s='';for(const b of bytes)s+=String.fromCharCode(b);return btoa(s);}
async function call(command,data){const prefix=data?"printf %s '"+b64(data)+"' | base64 -d | ":'';const result=JSON.parse(await shell(prefix+CLI+' '+command+(data?' stdin':'')));if(!result.ok)throw new Error(result.error||'操作失败，请重试');return result.result||result;}
function lines(id){return $(id).value.split('\n').map(x=>x.trim()).filter(Boolean);}
function wifiNames(){return $('wifiNames').value.split('\n').filter(x=>x.length>0);}
function resetPlan(){restoreDigest='';$('confirmBox').hidden=true;$('agree').checked=false;$('restore').disabled=true;}
function syncButtons(){
  const occupied=actionRunning||lastBusy||Date.now()<pendingLaunch;
  for(const id of ['backup','inspect','history','plan','export','test','saveSettings','addWifi'])$(id).disabled=occupied||!loaded||(id!=='saveSettings'&&id!=='addWifi'&&!configured);
  for(const id of ['plan','export'])$(id).disabled=$(id).disabled||!$('snapshots').value;
  $('restore').disabled=occupied||!restoreDigest||!$('agree').checked;
  $('rollback').disabled=occupied||!loaded||['none','rolled-back'].includes(restorePhase);
  $('clean').disabled=occupied||!loaded||!['none','complete','rolled-back','cancelled'].includes(restorePhase);
}
function schedule(){clearTimeout(pollTimer);if(!paused&&!document.hidden&&!actionRunning)pollTimer=setTimeout(()=>refresh(),lastBusy||Date.now()<pendingLaunch?1500:6000);}
function refresh(){
  if(refreshPromise)return refreshPromise;
  refreshPromise=(async()=>{
    try{
      const data=await call('status'),c=data.config||{},s=data.status||{},b=data.lastBackup||{},connection=data.connection||{};
      if(!loaded){
        for(const k of ['externalOrigin','internalOrigin','username'])$(k).value=c[k]||'';
        $('path').value=c.path||'/';for(const k of ['auto','allowHttp'])$(k).checked=!!c[k];
        for(const k of ['wifiNames','zipDirs','extraPaths'])$(k).value=(c[k]||[]).join('\n');
        text('passwordHint',c.passwordSaved?'已保存密码；留空会继续使用。':'密码仅保存在手机私有目录。');loaded=true;
      }
      configured=!!c.externalOrigin;lastBusy=!!data.busy;if(lastBusy)pendingLaunch=0;
      restorePhase=(data.restore||{}).phase||'none';
      const error=s.error||'',phase=s.phase||'尚未备份';text('status',phase);
      const state=error?'error':lastBusy?'running':b.time?'success':'idle';$('statusMarker').dataset.state=state;
      text('taskState',error?'等待处理':lastBusy?'执行中':'就绪');
      text('progress',lastBusy&&Number.isFinite(s.uploadedBytes)?'已上传并校验 '+size(s.uploadedBytes):error?'请查看错误信息后重试。':'安装或更新后自动检查，每日再补查。');
      text('lastTime',date(b.time));text('lastBytes',size(b.uploadedBytes));text('lastFiles',Number.isFinite(b.files)?b.files.toLocaleString('zh-CN'):'—');
      text('restoreState',phases[restorePhase]||restorePhase);
      if(error!==lastBackendError){lastBackendError=error;if(error)notify(error,true,'status');else if($('message').dataset.source==='status')notify('');}
      const mode=connection.mode==='internal'?'内网':connection.mode==='external'?'外网 / 默认':'未检查';text('routeBadge',mode);$('routeBadge').dataset.mode=connection.mode||'unknown';text('connectionMode',mode);
      text('connectionReason',connection.reason||'保存连接设置，检查后显示当前入口。');
      text('repositoryText',connection.peerPending?'当前入口已绑定，另一入口待连通后核验':connection.repositoryVerified?'备份库身份已验证，共用增量内容':'备份库尚未检查');
      text('version',c.toolVersion||'版本暂不可读');
    }catch(e){notify(e.message,true,'status');text('status','状态暂不可读');text('taskState','读取失败');}
    finally{syncButtons();}
  })().finally(()=>{refreshPromise=null;schedule();});return refreshPromise;
}
async function guarded(button,action){
  if(actionRunning)return;actionRunning=true;clearTimeout(pollTimer);button.setAttribute('aria-busy','true');syncButtons();
  try{notify('');await action();}catch(e){notify(e.message,true);}finally{button.removeAttribute('aria-busy');actionRunning=false;await refresh();syncButtons();schedule();}
}
async function launch(command,data){
  const pipe=data?"printf %s '"+b64(data)+"' | base64 -d | ":'';
  await shell("umask 077; ("+pipe+CLI+' '+command+(data?' stdin':'')+' > /data/adb/nas_module_backup/job.json 2>&1) < /dev/null &');
  pendingLaunch=Date.now()+2500;notify('任务已启动，后台继续执行。');
}
function showView(name,focus=true){
  for(const view of ['overview','snapshots','settings'])$('view-'+view).hidden=view!==name;
  for(const button of document.querySelectorAll('[data-view]')){if(button.dataset.view===name)button.setAttribute('aria-current','page');else button.removeAttribute('aria-current');}
  document.title=({overview:'概览',snapshots:'快照',settings:'设置'})[name]+' · NAS 模块备份';
  if(focus)$('title-'+name).focus({preventScroll:true});window.scrollTo(0,0);
}
for(const button of document.querySelectorAll('[data-view]'))button.onclick=()=>showView(button.dataset.view);
$('polling').onclick=()=>{paused=!paused;$('polling').setAttribute('aria-pressed',String(paused));text('polling',paused?'继续刷新':'暂停刷新');if(paused)clearTimeout(pollTimer);else refresh();};
document.addEventListener('visibilitychange',()=>{if(document.hidden)clearTimeout(pollTimer);else if(!paused)refresh();});
function validateField(id,message){const e=$(id);e.setAttribute('aria-invalid','true');e.setAttribute('aria-describedby','message');e.focus();throw new Error(message);}
$('settings').addEventListener('submit',event=>{
  event.preventDefault();guarded($('saveSettings'),async()=>{
    for(const id of ['externalOrigin','internalOrigin','path','wifiNames'])$(id).removeAttribute('aria-invalid');
    for(const id of ['externalOrigin','internalOrigin']){
      const value=$(id).value.trim();if(!value&&id==='internalOrigin')continue;let u;try{u=new URL(value);}catch(e){validateField(id,'请填写有效地址，例如 https://nas.example.com:5006');}
      if(!['http:','https:'].includes(u.protocol)||u.username||u.password||u.search||u.hash||!['','/'].includes(u.pathname))validateField(id,'地址只填写协议、主机与端口，目录请填共用路径。');
      if(u.protocol==='http:'&&!$('allowHttp').checked)validateField(id,'使用 HTTP 前请开启“允许 HTTP”。');
    }
    if(!$('path').value.trim().startsWith('/'))validateField('path','共用路径必须以 / 开头。');
    await call('configure',{externalOrigin:$('externalOrigin').value.trim(),internalOrigin:$('internalOrigin').value.trim(),path:$('path').value.trim(),wifiNames:wifiNames(),username:$('username').value,password:$('password').value,allowHttp:$('allowHttp').checked,auto:$('auto').checked,zipDirs:lines('zipDirs'),extraPaths:lines('extraPaths')});
    $('password').value='';resetPlan();notify('设置已保存。连接检查使用这份设置。');
  });
});
$('addWifi').onclick=()=>guarded($('addWifi'),async()=>{const r=await call('wifi');if(!r.ssid)throw new Error('暂未识别到 Wi-Fi，请手动填写名称。');$('wifiNames').value=[...new Set([...wifiNames(),r.ssid])].join('\n');notify('已添加当前 Wi-Fi，保存后生效。');});
$('test').onclick=()=>guarded($('test'),async()=>{await call('test');notify('WebDAV 连接、目录权限和备份库检查通过。');});
$('backup').onclick=()=>guarded($('backup'),()=>launch('backup'));
$('inspect').onclick=()=>guarded($('inspect'),async()=>{const r=await call('inspect');$('inventory').hidden=false;text('inventory','KernelSU 模块：'+r.modules.length+'\nLSPosed 模块应用：'+r.packages.length+'\n配置记录：'+JSON.stringify(r.lsposed.counts)+'\n外部配置：\n'+r.externalPaths.join('\n')+'\n\n'+r.warnings.join('\n'));});
$('history').onclick=()=>guarded($('history'),async()=>{
  const r=await call('history');$('snapshots').replaceChildren();resetPlan();
  for(const s of r.snapshots.sort((a,b)=>b.id.localeCompare(a.id))){const option=document.createElement('option');option.value=JSON.stringify(s);option.textContent=date(Number(s.id.split('-')[0]))+' · '+s.vault;$('snapshots').append(option);}
  $('historyEmpty').hidden=!!r.snapshots.length;if(!r.snapshots.length)notify('还没有已完成的快照，可以先开始备份。');
});
$('snapshots').onchange=()=>{resetPlan();syncButtons();};
$('plan').onclick=()=>guarded($('plan'),async()=>{
  resetPlan();if(!$('snapshots').value)throw new Error('请先刷新并选择快照。');const r=await call('plan',JSON.parse($('snapshots').value));$('planInfo').hidden=false;
  text('planInfo','恢复项目：'+r.roots+'\n文件记录：'+r.entries+'\n所需下载：'+size(r.downloadBytes)+'\n'+(r.blockers.length?'不能恢复：\n'+r.blockers.join('\n'):'环境检查通过')+'\n\n'+r.warnings.join('\n'));
  if(!r.blockers.length){restoreDigest=r.digest;$('confirmBox').hidden=false;}
});
$('agree').onchange=syncButtons;
$('restore').onclick=()=>guarded($('restore'),async()=>{if(!restoreDigest||!$('agree').checked)throw new Error('请先预检并确认恢复范围。');await launch('restore',{digest:restoreDigest});resetPlan();});
$('export').onclick=()=>guarded($('export'),async()=>{if(!$('snapshots').value)throw new Error('请先选择快照。');await launch('export',JSON.parse($('snapshots').value));});
$('rollback').onclick=()=>guarded($('rollback'),async()=>{if(confirm('回退本次恢复？重启后执行，解锁后完成应用数据回退。')){await call('rollback');notify('回退已安排，请按任务状态提示操作。');}});
$('clean').onclick=()=>guarded($('clean'),async()=>{if(confirm('删除本机恢复暂存和回退副本？NAS 历史快照会保留。')){await call('clean');notify('本机恢复暂存已清理。');}});
syncButtons();refresh();
