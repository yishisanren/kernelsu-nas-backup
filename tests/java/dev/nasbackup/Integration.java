package dev.nasbackup;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import android.database.sqlite.SQLiteDatabase;
import android.database.Cursor;
import android.system.Os;

public final class Integration {
    static int passed=0;static JSONArray results=new JSONArray();
    static void check(boolean ok,String name)throws Exception{Util.require(ok,"FAIL: "+name);passed++;results.put(name);System.out.println("PASS "+name);}
    interface Checked{void run()throws Exception;}
    static void rejects(Checked fn,String name)throws Exception{boolean rejected=false;try{fn.run();}catch(Exception x){rejected=true;}check(rejected,name);}
    static void file(Path p,String s)throws Exception{Util.mkdir(p.getParent());Files.write(p,s.getBytes(Util.UTF8));}
    static final class Fixture extends Inventory {
        Fixture(Path p){super(p,true);}
        @Override JSONArray packages()throws Exception{return new JSONArray().put(Util.obj("package","test.module","user",0,"uid",10123,"apks",new JSONArray().put("/data/app/test/base.apk")));}
    }
    public static void main(String[] args){Thread.setDefaultUncaughtExceptionHandler((t,x)->{x.printStackTrace();System.exit(1);});try{run(args);}catch(Throwable x){x.printStackTrace();System.exit(1);}}
    static void run(String[] args)throws Exception {
        Path base=Paths.get("/data/local/tmp/nasbackup-integration");Util.delete(base);Util.mkdir(base);Path source=base.resolve("source"),state=base.resolve("state");Util.mkdir(state);
        check(!Schedule.due(60000,0,"old","new",1,0),"变化先等待稳定窗口");
        check(Schedule.due(60001,0,"old","new",1,0),"安装更新稳定后触发备份");
        check(!Schedule.due(86399999,0,"same","same",0,0)&&Schedule.due(86400000,0,"same","same",0,0),"每日补查按成功时间触发");
        check(!Schedule.due(90000000,0,"old","new",0,90000001)&&Schedule.due(90000001,0,"old","new",0,90000001),"失败重试等待退避时间");
        file(source.resolve("data/adb/modules/demo/module.prop"),"id=demo\nname=Demo\nversion=1\nversionCode=1\n");
        Files.createSymbolicLink(source.resolve("data/adb/metamodule"),Paths.get("/data/adb/modules/demo"));
        file(source.resolve("data/adb/modules/demo/service.sh"),"#!/system/bin/sh\n# /data/adb/demo_config\n");Os.chmod(source.resolve("data/adb/modules/demo/service.sh").toString(),0755);
        file(source.resolve("data/adb/modules/demo/old.txt"),"first");file(source.resolve("data/adb/modules/demo/disable"),"");
        for(int i=0;i<24;i++)file(source.resolve("data/adb/modules/demo/parallel/"+i),"parallel-content-"+i);
        file(source.resolve("data/adb/modules/demo/parallel/duplicate"),"parallel-content-0");
        Files.write(source.resolve("data/adb/modules/demo/parallel/large"),new byte[16*1024*1024]);
        Path chunked=source.resolve("data/adb/modules/demo/parallel/chunked");
        try(java.io.OutputStream out=Files.newOutputStream(chunked)){byte[] block=new byte[Engine.CHUNK_SIZE];for(int i=0;i<5;i++){Arrays.fill(block,(byte)(i+1));out.write(block,0,i==4?1024*1024:block.length);}}
        String chunkedOriginalHash=Util.hash(chunked);
        Files.createSymbolicLink(source.resolve("data/adb/modules/demo/link"),Paths.get("old.txt"));
        Util.node(source.resolve("data/adb/modules/demo/null"),"char",259);Util.node(source.resolve("data/adb/modules/demo/pipe"),"fifo",0);
        file(source.resolve("data/adb/demo_config/settings.json"),"{\"v\":1}");
        SQLiteDatabase configDb=Db.open(source.resolve("data/adb/demo_config/prefs.db"),true);configDb.execSQL("CREATE TABLE preferences(k TEXT PRIMARY KEY,v TEXT)");configDb.enableWriteAheadLogging();configDb.execSQL("INSERT INTO preferences VALUES ('fixture','WAL value')");
        file(source.resolve("data/app/test/base.apk"),"fixture APK bytes - never installed");file(source.resolve("data/user/0/test.module/shared_prefs/a.xml"),"<map><string name=\"a\">value</string></map>");
        file(source.resolve("data/user/0/test.module/cache/ignored"),"cache");
        Path dbpath=source.resolve("data/adb/lspd/config/modules_config.db");Util.mkdir(dbpath.getParent());SQLiteDatabase db=Db.open(dbpath,true);
        for(String ddl:Lsp.CURRENT.values())db.execSQL(ddl);db.setVersion(101);db.enableWriteAheadLogging();
        db.execSQL("INSERT INTO modules VALUES ('test.module','/data/app/test/base.apk')");db.execSQL("INSERT INTO modules_state VALUES ('test.module',0,1,1)");db.execSQL("INSERT INTO scope VALUES ('test.module','android',0)");
        db.execSQL("INSERT INTO lspd_configs VALUES ('test',X'010203')");
        JSONObject config=Util.obj("url",args.length>0?args[0]:"http://127.0.0.1:18791/","allowHttp",true,"username","test","password","test","vault","fixture","extraPaths",new JSONArray(),"zipDirs",new JSONArray());
        file(state.resolve("spool/upload-interrupted-process"),"orphaned temporary bytes");
        Fixture inventory=new Fixture(source);Engine first=new Engine(state,inventory,config);JSONObject b1=first.backup();check(first.dav.uploadedObjects>0,"首次上传基线对象");
        check(!Files.exists(state.resolve("spool/upload-interrupted-process")),"进程中断遗留的上传临时文件在重试前清理");
        Path html=base.resolve("proxy.html");file(html,"<!doctype html><html>original object</html>");String htmlHash=Util.hash(html);long htmlSize=Files.size(html);
        Dav htmlDav=new Dav(config);htmlDav.put("control/transform-html",new byte[0]);htmlDav.object(html,htmlHash,htmlSize);
        check(!Util.hash(htmlDav.get(htmlDav.objectPath(htmlHash),1024)).equals(htmlHash),"夹具复现代理给HTML对象注入脚本");
        Path htmlRead=base.resolve("proxy-read.html");htmlDav.verifyRemote(htmlDav.objectPath(htmlHash),htmlHash,htmlSize,htmlRead);check(Util.hash(htmlRead).equals(htmlHash),"完整字节范围下载绕过HTML改写且哈希一致");
        htmlDav.put("control/bad-range",new byte[]{1});rejects(()->htmlDav.verifyRemote(htmlDav.objectPath(htmlHash),htmlHash,htmlSize,null),"拒绝206响应范围错误即使正文哈希正确");htmlDav.put("control/bad-range",new byte[0]);
        file(source.resolve("data/adb/modules_update/demo/module.prop"),"id=demo\nversionCode=2\n");check(inventory.modules().get(0).toString().contains("/modules/"),"安装未完成时不采集半成品更新目录");
        file(source.resolve("data/adb/modules/demo/update"),"");check(inventory.modules().get(0).toString().contains("/modules_update/"),"安装完成标记触发新版本采集");
        Engine pending=new Engine(state,inventory,config);JSONObject pendingResult=pending.backup();check(!pending.manifest("fixture",pendingResult.getString("id")).getJSONArray("roots").getJSONObject(0).getJSONObject("detail").getBoolean("enabled"),"待重启更新保留管理器中的禁用状态");
        Files.delete(source.resolve("data/adb/modules/demo/update"));Util.delete(source.resolve("data/adb/modules_update"));
        JSONObject manifest=first.manifest("fixture",b1.getString("id"));check(manifest.getJSONObject("lsposed").getJSONObject("tables").getJSONArray("scope").length()==1,"WAL未checkpoint时导出作用域");
        int parallelVerified=0;JSONObject chunkedEntry=null;for(int i=0;i<manifest.getJSONArray("entries").length();i++){JSONObject entry=manifest.getJSONArray("entries").getJSONObject(i);if(entry.getString("type").equals("file")&&entry.getString("path").startsWith("parallel/")){JSONArray objects=Engine.objects(entry);for(int n=0;n<objects.length();n++){JSONObject object=objects.getJSONObject(n);first.dav.verifyRemote(first.dav.objectPath(object.getString("hash")),object.getString("hash"),object.getLong("size"),null);}parallelVerified++;if(entry.has("chunks"))chunkedEntry=entry;}}
        check(parallelVerified==27,"并行队列跨批次、重复内容和大文件屏障均完整回读");
        check(chunkedEntry!=null&&chunkedEntry.getJSONArray("chunks").length()==5,"33MiB文件以五个块通过32MiB网关上传限制");
        JSONObject badChunks=new JSONObject(manifest.toString());for(int i=0;i<badChunks.getJSONArray("entries").length();i++){JSONObject e=badChunks.getJSONArray("entries").getJSONObject(i);if(e.has("chunks"))e.getJSONArray("chunks").getJSONObject(0).put("size",1);}
        rejects(()->Engine.validate(badChunks),"分块大小合计不符时拒绝清单");
        JSONObject reversed=new JSONObject(manifest.toString());for(int i=0;i<reversed.getJSONArray("entries").length();i++){JSONObject e=reversed.getJSONArray("entries").getJSONObject(i);if(e.has("chunks")){JSONArray c=e.getJSONArray("chunks");JSONObject a=c.getJSONObject(0),b=c.getJSONObject(1);c.put(0,b);c.put(1,a);}}
        rejects(()->new Engine(base.resolve("reversed-stage"),inventory,config).stage(reversed),"分块顺序被改动时整文件哈希校验拒绝恢复准备");
        JSONObject legacy=new JSONObject(manifest.toString()).put("format",1);for(int i=legacy.getJSONArray("entries").length()-1;i>=0;i--)if(legacy.getJSONArray("entries").getJSONObject(i).has("chunks"))legacy.getJSONArray("entries").remove(i);
        new Engine(base.resolve("legacy-stage"),inventory,config).stage(legacy);check(true,"第一版未分块快照仍可下载并准备恢复");
        try(java.util.stream.Stream<Path> spools=Files.list(state.resolve("spool"))){check(spools.count()==0,"并行备份成功后清理所有临时对象");}
        JSONObject stale=new JSONObject(manifest.getJSONObject("lsposed").toString()),st=stale.getJSONObject("tables");
        st.getJSONArray("modules").put(Util.obj("module_pkg_name","test.removed","apk_path","/data/app/old/base.apk"));
        st.getJSONArray("modules_state").put(Util.obj("module_pkg_name","test.removed","user_id",0,"enabled",1,"scope_request_blocked",1));
        st.getJSONArray("scope").put(Util.obj("module_pkg_name","test.removed","app_pkg_name","android","user_id",0));
        st.getJSONArray("module_configs").put(Util.obj("module_pkg_name","test.removed","user_id",0,"group_name","prefs","key_name","saved","data",Util.obj("blob","AQID")));
        Lsp.write(stale,state.resolve("stale-modern.db"),Collections.singletonMap("test.module","/data/app/new/base.apk"));JSONObject sm=Lsp.read(state.resolve("stale-modern.db")).getJSONObject("tables");
        check(sm.getJSONArray("modules_state").getJSONObject(0).getInt("enabled")==1&&sm.getJSONArray("modules_state").getJSONObject(1).getInt("enabled")==0,"已卸载残留保持禁用且不改变已安装模块状态");
        check(sm.getJSONArray("scope").length()==2&&sm.getJSONArray("module_configs").getJSONObject(0).getJSONObject("data").getString("blob").equals("AQID"),"已卸载残留配置BLOB和作用域完整保留");
        check(sm.getJSONArray("modules").getJSONObject(1).getString("apk_path").equals(Lsp.unavailablePath("test.removed")),"缺少APK不沿用失效安装路径");
        JSONObject old=Util.obj("present",true,"variant","legacy","version",93,"tables",Util.obj("modules",new JSONArray().put(Util.obj("mid",1,"module_pkg_name","test.removed","apk_path","/old/base.apk","enabled",1)),"scope",new JSONArray().put(Util.obj("mid",1,"app_pkg_name","android","user_id",0)),"configs",new JSONArray().put(Util.obj("module_pkg_name","test.removed","user_id",0,"group","prefs","key","saved","data",Util.obj("blob","AQID")))));
        Lsp.write(old,state.resolve("stale-legacy.db"),Collections.emptyMap());JSONObject sl=Lsp.read(state.resolve("stale-legacy.db")).getJSONObject("tables");
        check(sl.getJSONArray("modules").getJSONObject(0).getInt("enabled")==0&&sl.getJSONArray("scope").length()==1&&sl.getJSONArray("configs").getJSONObject(0).getJSONObject("data").getString("blob").equals("AQID"),"旧版三表结构也保留禁用残留配置");
        JSONObject staleManifest=new JSONObject(manifest.toString()).put("lsposed",stale);String unchanged=staleManifest.toString();JSONObject stalePlan=first.plan(staleManifest);
        check(stalePlan.getJSONArray("warnings").length()==manifest.getJSONArray("warnings").length()+1&&unchanged.equals(staleManifest.toString()),"恢复预检提示缺失APK且不修改原快照");
        check(first.dav.list("vaults/fixture/snapshots/").contains(b1.getString("id")+".sha256"),"PROPFIND发现已提交清单");
        Engine second=new Engine(state,inventory,config);second.backup();check(second.dav.uploadedObjects==0&&second.dav.uploadedBytes==0,"未变化备份零对象上传");
        file(source.resolve("data/adb/modules/demo/old.txt"),"changed");file(source.resolve("data/adb/modules/demo/new.txt"),"new");Files.delete(source.resolve("data/adb/modules/demo/disable"));
        try(java.io.RandomAccessFile f=new java.io.RandomAccessFile(chunked.toFile(),"rw")){f.seek(Engine.CHUNK_SIZE+100);f.write(99);}
        db.execSQL("UPDATE modules_state SET enabled=0");db.execSQL("INSERT INTO scope VALUES ('test.module','test.target',0)");
        Engine third=new Engine(state,inventory,config);JSONObject b3=third.backup();check(third.dav.uploadedObjects==3,"两个小文件和大文件一个变化块仅上传三个对象");
        JSONObject changed=third.manifest("fixture",b3.getString("id"));check(changed.getJSONObject("lsposed").getJSONObject("tables").getJSONArray("scope").length()==2,"作用域变化进入新清单");
        file(source.resolve("data/adb/modules/demo/interrupted.txt"),"interrupted-transfer-content");first.dav.put("control/fail-next-object",new byte[0]);
        int beforeSnapshots=first.dav.list("vaults/fixture/snapshots/").size();
        rejects(()->new Engine(state,inventory,config).backup(),"模拟上传中断返回失败");check(first.dav.list("vaults/fixture/snapshots/").size()==beforeSnapshots,"中断不发布不完整快照");
        try(java.util.stream.Stream<Path> spools=Files.list(state.resolve("spool"))){check(spools.count()==0,"并行备份失败后取消任务并清理临时对象");}
        Engine retry=new Engine(state,inventory,config);retry.backup();check(retry.dav.uploadedObjects==1,"重试修复半写对象且复用已有文件");
        Path target=base.resolve("target"),restoreState=base.resolve("restore-state");Util.mkdir(restoreState);
        file(target.resolve("data/adb/modules/demo/before.txt"),"before restore");file(target.resolve("data/adb/demo_config/before.txt"),"old config");file(target.resolve("data/user/0/test.module/original.txt"),"original app");
        Lsp.write(manifest.getJSONObject("lsposed"),target.resolve("data/adb/lspd/config/modules_config.db"),Collections.singletonMap("test.module","/data/app/prior/base.apk"));
        Engine re=new Engine(restoreState,new Fixture(target),config);Restore restore=new Restore(re);restore.prepare(manifest);check(restore.status().getString("phase").equals("awaiting-reboot"),"恢复准备完成并等待重启");
        new Restore(new Engine(restoreState,new Fixture(target),new JSONObject(),false)).early();restore.finishApps();check(new String(Files.readAllBytes(target.resolve("data/adb/modules/demo/old.txt")),Util.UTF8).equals("first"),"离线启动阶段无需NAS配置即可还原内容");
        check(Util.hash(target.resolve("data/adb/modules/demo/parallel/chunked")).equals(chunkedOriginalHash),"大文件从分块恢复后与原文件哈希完全一致");
        check(Files.isSymbolicLink(target.resolve("data/adb/metamodule"))&&Files.readSymbolicLink(target.resolve("data/adb/metamodule")).toString().equals("/data/adb/modules/demo"),"重建KernelSU挂载引擎入口");
        check(Files.exists(target.resolve("data/adb/modules/demo/disable"))&&!Files.exists(target.resolve("data/adb/modules/demo/new.txt")),"模块禁用状态及删除记录还原");
        check(Files.isSymbolicLink(target.resolve("data/adb/modules/demo/link"))&&Files.readSymbolicLink(target.resolve("data/adb/modules/demo/link")).toString().equals("old.txt"),"链接不跟随并正确恢复");
        check((Os.stat(target.resolve("data/adb/modules/demo/service.sh").toString()).st_mode&0777)==0755,"脚本执行权限还原");
        check(Inventory.meta(target.resolve("data/adb/modules/demo/null")).getString("type").equals("char")&&Inventory.meta(target.resolve("data/adb/modules/demo/pipe")).getString("type").equals("fifo"),"普通设备节点及FIFO还原");
        check(Files.exists(target.resolve("data/user/0/test.module/shared_prefs/a.xml"))&&!Files.exists(target.resolve("data/user/0/test.module/cache/ignored")),"应用配置恢复并排除缓存");
        SQLiteDatabase restoredDb=Db.open(target.resolve("data/adb/demo_config/prefs.db"),false);try(Cursor cursor=restoredDb.rawQuery("SELECT v FROM preferences WHERE k='fixture'",null)){check(cursor.moveToFirst()&&cursor.getString(0).equals("WAL value"),"普通配置SQLite包含WAL并可独立恢复");}restoredDb.close();
        JSONObject lsp=Lsp.read(target.resolve("data/adb/lspd/config/modules_config.db"));check(lsp.getJSONObject("tables").getJSONArray("modules_state").getJSONObject(0).getInt("enabled")==0,"应用配置恢复完成前暂停LSPosed模块");
        restore.early();lsp=Lsp.read(target.resolve("data/adb/lspd/config/modules_config.db"));check(lsp.getJSONObject("tables").getJSONArray("modules_state").getJSONObject(0).getInt("enabled")==1&&restore.status().getString("phase").equals("complete"),"最终重启恢复LSPosed启用及作用域");
        check(lsp.getJSONObject("tables").getJSONArray("modules").getJSONObject(0).getString("apk_path").equals("/data/app/test/test.module/base.apk"),"重建APK安装路径");
        restore.requestRollback();restore.early();restore.rollbackApps();check(Files.exists(target.resolve("data/adb/modules/demo/before.txt"))&&!Files.exists(target.resolve("data/adb/modules/demo/old.txt")),"模块回退到恢复前内容");
        check(Files.exists(target.resolve("data/user/0/test.module/original.txt"))&&!Files.exists(target.resolve("data/user/0/test.module/shared_prefs/a.xml")),"应用配置回退");
        check(!Files.exists(target.resolve("data/adb/metamodule"),LinkOption.NOFOLLOW_LINKS),"回退清理本次新建挂载入口");
        check(restore.status().getString("phase").equals("rollback-final-reboot"),"回退应用数据后等待重新启用模块");restore.early();
        check(Lsp.read(target.resolve("data/adb/lspd/config/modules_config.db")).getJSONObject("tables").getJSONArray("modules").getJSONObject(0).getString("apk_path").equals("/data/app/prior/base.apk"),"回退还原原始LSPosed数据库");
        check(restore.status().getString("phase").equals("rolled-back"),"回退任务完成");restore.clean();
        Path sentinel=base.resolve("permissions");Files.createDirectory(sentinel);Os.chmod(sentinel.toString(),0751);Util.mkdir(sentinel);check((Os.stat(sentinel.toString()).st_mode&0777)==0751,"不改变已存在父目录权限");
        restore.prepare(manifest);JSONObject interrupted=restore.status();interrupted.put("phase","applying");restore.save(interrupted);
        JSONObject firstRoot=manifest.getJSONArray("roots").getJSONObject(0);restore.move(interrupted,restore.payload(manifest,firstRoot),target.resolve(firstRoot.getString("destination").substring(1)),"interrupted");
        restore.early();restore.rollbackApps();restore.early();check(Files.exists(target.resolve("data/adb/modules/demo/before.txt")),"应用中断后下次启动自动回退");restore.clean();
        JSONObject attack=new JSONObject(manifest.toString());attack.getJSONArray("entries").getJSONObject(0).put("path","../../escape");rejects(()->Engine.validate(attack),"拒绝清单路径穿越");
        JSONObject overlap=new JSONObject(manifest.toString());overlap.getJSONArray("roots").put(Util.obj("key","overlap","kind","extra","destination","/data/adb/demo_config/nested","detail",new JSONObject()));rejects(()->Engine.validate(overlap),"拒绝嵌套恢复根");
        JSONObject wrong=new JSONObject(config.toString()).put("password","wrong");rejects(()->new Dav(wrong).list("vaults/"),"错误凭据失败");
        rejects(()->Inventory.validateExtra("/data/adb/."),"拒绝配置路径指向整个adb目录");
        rejects(()->Util.beneath(base,"a/../b"),"拒绝相对路径归一化别名");
        if(args.length>1){boolean denied=false;try{new Dav(new JSONObject(config.toString()).put("url",args[1])).list("vaults/");}catch(javax.net.ssl.SSLException ex){denied=true;}check(denied,"HTTPS拒绝未受信任证书");}
        rejects(()->first.dav.get("redirect",100),"拒绝重定向转发凭据");
        JSONObject corrupt=manifest.getJSONArray("entries").getJSONObject(1);for(int i=0;i<manifest.getJSONArray("entries").length();i++){JSONObject x=manifest.getJSONArray("entries").getJSONObject(i);if(x.getString("type").equals("file")&&x.getLong("size")>0){corrupt=x;break;}}
        String objectPath=first.dav.objectPath(corrupt.getString("hash"));byte[] originalObject=first.dav.get(objectPath,1024*1024);first.dav.put(objectPath,new byte[(int)corrupt.getLong("size")]);
        rejects(()->new Engine(restoreState,new Fixture(target),config).stage(manifest),"NAS对象损坏阻止恢复");check(Files.exists(target.resolve("data/adb/modules/demo/before.txt")),"校验失败不改现有目标");
        first.dav.put(objectPath,originalObject);Path resumed=new Engine(restoreState,new Fixture(target),config).stage(manifest);check(Files.exists(resumed.resolve("roots/module-demo/module.prop")),"中断下载可复用暂存并继续恢复");
        db.close();configDb.close();JSONObject result=Util.obj("passed",passed,"checks",results,"device",Util.prop("ro.product.device"),"api",Util.prop("ro.build.version.sdk"));Util.write(base.resolve("result.json"),result);System.out.println(result);
    }
}
