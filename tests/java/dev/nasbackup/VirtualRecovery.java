package dev.nasbackup;

import java.nio.file.*;
import java.util.*;
import org.json.*;
import android.system.Os;
import android.database.sqlite.SQLiteDatabase;

/** Destructive fixtures are permitted only inside a disposable Android emulator. */
public final class VirtualRecovery {
    static final String PKG="dev.nasbackup.fixture",ID="nasbackup_virtual_recovery";
    static final Path BASE=Paths.get("/data/local/tmp/nasbackup-virtual-recovery"),STATE=BASE.resolve("state");
    static final Path MODULE=Paths.get("/data/adb/modules/"+ID),EXTRA=Paths.get("/data/adb/"+ID);
    static final Path DB=Paths.get("/data/adb/lspd/config/modules_config.db");
    static int checks;
    static void check(boolean value,String message)throws Exception{Util.require(value,message);checks++;}
    static void file(Path p,String value)throws Exception{Util.mkdir(p.getParent());Files.write(p,value.getBytes(Util.UTF8));}
    static Inventory inventory(){return new Inventory(Paths.get("/"),false){
        @Override JSONObject environment()throws Exception{return Util.obj("device","disposable-emulator","api",Util.prop("ro.build.version.sdk"),"abi",Util.prop("ro.product.cpu.abi"),"fingerprint","virtual-recovery-fixture","ksu","simulated","lsposed","simulated");}
        @Override List<Path> modules(){return Arrays.asList(MODULE);}
        @Override Set<String> external(List<Path> m,JSONObject c,JSONArray w){return new TreeSet<>(Arrays.asList(EXTRA.toString()));}
        @Override String metamodule(){return "";}
        @Override String quick(){return "stable-virtual-fixture";}
        @Override JSONArray packages()throws Exception{return new JSONArray().put(Util.obj("package",PKG,"user",0,"uid",Restore.uid(PKG,0),"apks",new JSONArray(Restore.apkPaths(PKG,0))));}
    };}
    static Engine engine(boolean network)throws Exception{return new Engine(STATE,inventory(),network?Util.read(STATE.resolve("config.json")):new JSONObject(),network);}
    static JSONObject manifest()throws Exception{return Util.read(STATE.resolve("expected.json"));}
    static void prepare(String url)throws Exception{
        check(!Files.exists(BASE)&&!Files.exists(MODULE)&&!Files.exists(EXTRA)&&!Files.exists(DB),"虚拟环境必须没有旧测试或LSPosed数据库");
        check(Restore.apkPaths(PKG,0).isEmpty(),"虚拟环境必须没有测试应用");
        Util.mkdir(STATE);file(BASE.resolve("owner"),"nasbackup-virtual-v1");
        Util.write(STATE.resolve("config.json"),Util.obj("url",url,"allowHttp",true,"username","test","password","test","vault","virtual-recovery","zipDirs",new JSONArray(),"extraPaths",new JSONArray()));
        check(Util.run("/system/bin/pm","install","-t","/data/local/tmp/nasbackup-fixture.apk").contains("Success"),"建立测试应用失败");
        int uid=Restore.uid(PKG,0);
        file(MODULE.resolve("module.prop"),"id="+ID+"\nname=Virtual recovery\nversion=1\nversionCode=1\n");
        file(MODULE.resolve("service.sh"),"#!/system/bin/sh\nprintf recovered > "+BASE+"/module-started\n");Os.chmod(MODULE.resolve("service.sh").toString(),0755);
        Files.createSymbolicLink(MODULE.resolve("relative-link"),Paths.get("module.prop"));Util.node(MODULE.resolve("pipe"),"fifo",0);
        for(int i=0;i<120;i++)file(EXTRA.resolve("small/"+i),"fixture-"+i);
        file(EXTRA.resolve("settings.json"),"{\"answer\":42}");
        for(String area:Arrays.asList("user","user_de")){
            Path p=Paths.get("/data/"+area+"/0/"+PKG+"/shared_prefs/recovery.xml");file(p,"<map><int name=\"answer\" value=\"42\"/></map>");Os.chown(p.getParent().toString(),uid,uid);Os.chown(p.toString(),uid,uid);
        }
        Util.mkdir(DB.getParent());SQLiteDatabase db=Db.open(DB,true);
        try{for(String ddl:Lsp.CURRENT.values())db.execSQL(ddl);db.setVersion(101);
            db.execSQL("INSERT INTO modules VALUES (?,?)",new Object[]{PKG,Restore.apkPaths(PKG,0).get(0)});
            db.execSQL("INSERT INTO modules_state VALUES (?,0,1,0)",new Object[]{PKG});db.execSQL("INSERT INTO scope VALUES (?,'android',0)",new Object[]{PKG});db.execSQL("INSERT INTO lspd_configs VALUES ('fixture',X'010203')");
        }finally{db.close();}
        Engine e=engine(true);JSONObject saved=e.backup(),m=e.manifest("virtual-recovery",saved.getString("id"));Util.write(STATE.resolve("expected.json"),m);
        check(Util.run("/system/bin/pm","uninstall",PKG).contains("Success"),"删除源测试应用失败");Util.delete(MODULE);Util.delete(EXTRA);Util.delete(DB.getParent());
        check(!Files.exists(MODULE)&&!Files.exists(EXTRA)&&Restore.apkPaths(PKG,0).isEmpty(),"恢复前必须是空目标");
        check(!Files.exists(STATE.resolve("restore-stage")),"正式恢复下载暂存必须为空");
        long start=System.currentTimeMillis();Restore restore=new Restore(engine(true));restore.prepare(m);long elapsed=System.currentTimeMillis()-start;
        check(restore.status().getString("phase").equals("awaiting-reboot"),"正式恢复准备阶段失败");
        check(!Restore.apkPaths(PKG,0).isEmpty(),"正式恢复没有重新安装测试APK");
        JSONObject cacheInodes=new JSONObject();for(String area:Arrays.asList("user","user_de"))for(String cache:Arrays.asList("cache","code_cache")){Path p=Paths.get("/data/"+area+"/0/"+PKG+"/"+cache);cacheInodes.put(p.toString(),Os.stat(p.toString()).st_ino);}Util.write(STATE.resolve("cache-inodes.json"),cacheInodes);
        check(!Files.exists(MODULE)&&!Files.exists(EXTRA),"重启前不能提前替换模块配置");
        boolean rejected=false;try{restore.early();}catch(Exception expected){rejected=true;}check(rejected,"同一次启动不能越过重启门禁");
        Util.write(STATE.resolve("prepare-result.json"),Util.obj("passed",true,"checks",checks,"phase",restore.status().getString("phase"),"formalDownloadMilliseconds",elapsed,"extraPrefetchUsed",false,"cleanTargetBeforeRestore",true,"realPackageManagerInstall",true));
        System.out.println(Util.read(STATE.resolve("prepare-result.json")));
    }
    static void apply()throws Exception{
        Restore r=new Restore(engine(false));check(r.status().getString("phase").equals("awaiting-reboot"),"首次启动阶段错误");r.early();
        check(Lsp.read(DB).getJSONObject("tables").getJSONArray("modules_state").getJSONObject(0).getInt("enabled")==0,"应用数据恢复前必须暂停模块");
        r.finishApps();check(r.status().getString("phase").equals("awaiting-final-reboot"),"解锁后配置恢复阶段错误");
        verifyCacheInodes();
        check(Files.readAllBytes(Paths.get("/data/user/0/"+PKG+"/shared_prefs/recovery.xml")).length>0,"真实CE配置没有恢复");
        boolean rejected=false;try{r.early();}catch(Exception expected){rejected=true;}check(rejected,"最终启用不能越过第二次重启门禁");
        System.out.println(Util.obj("passed",true,"checks",checks,"phase",r.status().getString("phase"),"networkRequiredAfterPrepare",false));
    }
    static void finish()throws Exception{
        Restore r=new Restore(engine(false));r.early();check(r.status().getString("phase").equals("complete"),"第二次启动未完成恢复");
        verifyCacheInodes();
        JSONObject m=manifest();Map<String,JSONObject> roots=new HashMap<>();for(JSONObject root:Restore.roots(m))roots.put(root.getString("key"),root);
        JSONObject uids=r.status().getJSONObject("uidMap");JSONArray entries=m.getJSONArray("entries");int files=0;
        for(int i=0;i<entries.length();i++){
            JSONObject entry=entries.getJSONObject(i),root=roots.get(entry.getString("root"));String kind=root.getString("kind"),type=entry.getString("type");
            Path base=kind.equals("apk")?r.payload(m,root):Paths.get(root.getString("destination"));Path p=entry.getString("path").isEmpty()?base:base.resolve(entry.getString("path"));JSONObject actual=Inventory.meta(p);
            check(actual.getString("type").equals(type),"恢复类型错误");
            if(type.equals("file")){check(Util.hash(p).equals(entry.getString("hash")),"恢复文件哈希错误");files++;}
            if(type.equals("link"))check(Files.readSymbolicLink(p).toString().equals(entry.getString("target")),"恢复符号链接错误");
            if(!kind.equals("apk")&&!(kind.startsWith("app_")&&entry.getString("path").isEmpty())){
                check(actual.getInt("uid")==uids.optInt(Integer.toString(entry.getInt("uid")),entry.getInt("uid")),"恢复UID映射错误");
                check(actual.getInt("gid")==uids.optInt(Integer.toString(entry.getInt("gid")),entry.getInt("gid")),"恢复GID映射错误");
                if(!type.equals("link"))check(actual.getInt("mode")==entry.getInt("mode"),"恢复权限错误");
            }
        }
        JSONObject tables=Lsp.read(DB).getJSONObject("tables");check(tables.length()==6,"恢复LSPosed表数错误");
        check(tables.getJSONArray("modules_state").getJSONObject(0).getInt("enabled")==1,"恢复启用状态错误");
        check(tables.getJSONArray("scope").getJSONObject(0).getString("app_pkg_name").equals("android"),"恢复作用域错误");
        check(tables.getJSONArray("lspd_configs").getJSONObject(0).getJSONObject("data").getString("blob").equals("AQID"),"恢复BLOB错误");
        check(tables.getJSONArray("modules").getJSONObject(0).getString("apk_path").equals(Restore.apkPaths(PKG,0).get(0)),"APK路径重映射错误");
        Util.run("/system/bin/sh",MODULE.resolve("service.sh").toString());check(new String(Files.readAllBytes(BASE.resolve("module-started")),Util.UTF8).equals("recovered"),"恢复后的模块脚本无法运行");
        System.out.println(Util.obj("passed",true,"checks",checks,"filesVerified",files,"phase",r.status().getString("phase"),"realEmulatorReboots",2,"realPackageManagerInstall",true,"cacheDirectoryInodesPreserved",true,"kernelSuStartupHookSimulated",true,"lsposedRuntimeHookingTested",false,"extraPrefetchUsed",false,"physicalDeviceModified",false));
    }
    static void verifyCacheInodes()throws Exception{JSONObject expected=Util.read(STATE.resolve("cache-inodes.json"));Iterator<String> paths=expected.keys();while(paths.hasNext()){String p=paths.next();check(Os.stat(p).st_ino==expected.getLong(p),"缓存目录inode被替换");}}
    public static void main(String[] args)throws Exception{
        Thread.setDefaultUncaughtExceptionHandler((t,x)->{x.printStackTrace();System.exit(1);});
        check(Util.prop("ro.kernel.qemu").equals("1")&&Os.getuid()==0,"仅允许一次性Root Android模拟器");
        if(args[0].equals("prepare"))prepare(args[1]);else{
            check(new String(Files.readAllBytes(BASE.resolve("owner")),Util.UTF8).equals("nasbackup-virtual-v1"),"测试目录所有者不匹配");
            if(args[0].equals("apply"))apply();else if(args[0].equals("finish"))finish();else throw new IllegalArgumentException();
        }
    }
}
