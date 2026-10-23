package dev.nasbackup;

import android.database.sqlite.SQLiteDatabase;
import android.database.DatabaseUtils;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Opt-in real NAS verification. Writes only below an owned, private temporary root. */
public final class NasRestoreVerify {
    static final Path BASE=Paths.get("/data/local/tmp/nasbackup-nas-verification");
    static void check(boolean ok,String message)throws Exception{Util.require(ok,message);}
    static Path own()throws Exception{
        Path marker=BASE.resolve("owner");
        if(Files.exists(BASE,LinkOption.NOFOLLOW_LINKS))check(!Files.isSymbolicLink(BASE)&&Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)&&new String(Files.readAllBytes(marker),Util.UTF8).equals("nasbackup-verifier-v1"),"隔离目录已被其他内容占用");
        else {Util.mkdir(BASE);Util.write(marker,"nasbackup-verifier-v1".getBytes(Util.UTF8));}
        return BASE;
    }
    static Path entryPath(JSONObject root,JSONObject entry,Path base)throws Exception{
        String relative=entry.getString("path");return relative.isEmpty()?base:base.resolve(relative);
    }
    static int newEntries(String key,String kind,Path base,Path p,Set<String> expected)throws Exception{
        if(Engine.cachePath(base,p,kind))return 0;String name=p.getFileName().toString();
        if(kind.equals("module")&&Arrays.asList("update","remove",".hot_install").contains(name))return 0;
        for(String suffix:Arrays.asList("-wal","-shm","-journal"))if(name.endsWith(suffix)&&Engine.sqlite(p.resolveSibling(name.substring(0,name.length()-suffix.length()))))return 0;
        JSONObject meta=Inventory.meta(p);if(meta.getString("type").equals("socket"))return 0;
        int count=expected.contains(key+"/"+base.relativize(p))?0:1;
        if(meta.getString("type").equals("dir"))try(DirectoryStream<Path> children=Files.newDirectoryStream(p)){for(Path child:children)count+=newEntries(key,kind,base,child,expected);}
        return count;
    }
    static void verifyRestored(JSONObject manifest,Restore restore,Path target)throws Exception{
        Map<String,JSONObject> roots=new HashMap<>();for(JSONObject r:Restore.roots(manifest))roots.put(r.getString("key"),r);
        JSONArray entries=manifest.getJSONArray("entries");int files=0,links=0,nodes=0,metadata=0;
        for(int i=0;i<entries.length();i++){
            JSONObject e=entries.getJSONObject(i),r=roots.get(e.getString("root"));String kind=r.getString("kind"),type=e.getString("type");
            Path base=(kind.equals("apk")||kind.equals("original_zip"))?restore.payload(manifest,r):restore.target(r.getString("destination"));
            Path p=entryPath(r,e,base);check(p.normalize().startsWith(BASE),"隔离验证路径越界");
            JSONObject actual=Inventory.meta(p);check(actual.getString("type").equals(type),"恢复条目类型不一致");
            if(type.equals("file")){check(Files.size(p)==e.getLong("size")&&Util.hash(p).equals(e.getString("hash")),"恢复文件内容不一致");files++;}
            if(type.equals("link")){check(Files.readSymbolicLink(p).toString().equals(e.getString("target")),"恢复链接目标不一致");links++;}
            if(type.equals("char")){check(actual.getLong("rdev")==e.getLong("rdev"),"恢复设备节点不一致");nodes++;}
            if(type.equals("fifo")||type.equals("whiteout"))nodes++;
            if(!kind.equals("apk")&&!kind.equals("original_zip")&&!(kind.startsWith("app_")&&e.getString("path").isEmpty())){
                check(actual.getInt("uid")==e.getInt("uid")&&actual.getInt("gid")==e.getInt("gid"),"恢复属主不一致");
                if(!type.equals("link")){check(actual.getInt("mode")==e.getInt("mode"),"恢复权限不一致");for(String attr:Arrays.asList("trusted.overlay.opaque","security.capability","security.selinux"))if(e.has(attr))check(e.getString(attr).equals(actual.optString(attr)),"恢复扩展属性不一致");}metadata++;
            }
        }
        JSONObject lsp=manifest.getJSONObject("lsposed");int rows=0,tables=0;
        if(lsp.optBoolean("present")){
            Map<String,String> apks=new HashMap<>();JSONArray packages=manifest.getJSONArray("packages");for(int i=0;i<packages.length();i++){String pkg=packages.getJSONObject(i).getString("package");apks.put(pkg,"/data/app/test/"+pkg+"/base.apk");}
            JSONObject a=new JSONObject(lsp.toString()),b=Lsp.read(target.resolve("data/adb/lspd/config/modules_config.db"));
            Set<String> missing=Lsp.missingApks(lsp,apks.keySet());Set<Integer> skipped=new HashSet<>();JSONArray skip=lsp.optJSONArray("skippedUsers");if(skip!=null)for(int i=0;i<skip.length();i++)skipped.add(skip.getInt(i));
            JSONObject expectedTables=a.getJSONObject("tables");JSONArray moduleRows=expectedTables.getJSONArray("modules");
            for(int i=0;i<moduleRows.length();i++){JSONObject row=moduleRows.getJSONObject(i);String pkg=row.getString("module_pkg_name");row.put("apk_path",missing.contains(pkg)?Lsp.unavailablePath(pkg):apks.get(pkg));}
            boolean modern=a.getString("variant").equals("modern");JSONArray states=expectedTables.getJSONArray(modern?"modules_state":"modules");
            for(int i=0;i<states.length();i++){JSONObject row=states.getJSONObject(i);if(missing.contains(row.getString("module_pkg_name"))||(modern&&skipped.contains(row.optInt("user_id",-1))))row.put("enabled",0);}
            if(!modern){JSONArray scopes=expectedTables.getJSONArray("scope"),kept=new JSONArray();for(int i=0;i<scopes.length();i++)if(!skipped.contains(scopes.getJSONObject(i).getInt("user_id")))kept.put(scopes.getJSONObject(i));expectedTables.put("scope",kept);}
            check(a.getInt("version")==b.getInt("version"),"恢复数据库版本不一致");
            Iterator<String> names=a.getJSONObject("tables").keys();while(names.hasNext()){String name=names.next();JSONArray values=a.getJSONObject("tables").getJSONArray(name);check(RealRoundTrip.rows(values).equals(RealRoundTrip.rows(b.getJSONObject("tables").getJSONArray(name))),"恢复数据库记录不一致");tables++;rows+=values.length();}
        }
        JSONObject result=Util.obj("snapshot",manifest.getString("id"),"passed",true,"files",files,"links",links,"nodes",nodes,"metadataEntries",metadata,"lsposedTables",tables,"lsposedRows",rows,"skippedUsers",manifest.optJSONArray("skippedUsers"),"productionRestorePerformed",false,"realRebootPerformed",false,"apkInstallationSimulated",true);
        Util.write(restore.state.resolve("verification.json"),result);System.out.println(result);
    }
    static void compare(Engine engine,JSONObject manifest)throws Exception{
        Map<String,Path> sources=new HashMap<>();Inventory live=new Inventory(Paths.get("/"),false);Map<String,Path> modules=new HashMap<>();for(Path p:live.modules())modules.put(p.getFileName().toString(),p);
        for(JSONObject r:Restore.roots(manifest))sources.put(r.getString("key"),r.getString("kind").equals("module")?modules.get(r.getJSONObject("detail").getString("id")):Paths.get(r.getString("destination")));
        int same=0,changed=0,missing=0,unreadable=0,sqlite=0,metadataChanged=0;JSONArray samples=new JSONArray(),entries=manifest.getJSONArray("entries");
        for(int i=0;i<entries.length();i++){
            JSONObject e=entries.getJSONObject(i);Path base=sources.get(e.getString("root"));
            if(base==null){missing++;continue;}Path p=base.resolve(e.getString("path"));
            if(!Files.exists(p,LinkOption.NOFOLLOW_LINKS)){missing++;continue;}
            try{
                JSONObject current=Inventory.meta(p);boolean equal=current.getString("type").equals(e.getString("type"));
                if(equal&&e.getString("type").equals("file")){
                    if(Engine.sqlite(p)){Path temp=engine.state.resolve("compare.sqlite");Files.deleteIfExists(temp);SQLiteDatabase db=Db.open(p,false);try{db.execSQL("VACUUM INTO "+DatabaseUtils.sqlEscapeString(temp.toString()));}finally{db.close();}equal=Files.size(temp)==e.getLong("size")&&Util.hash(temp).equals(e.getString("hash"));Files.deleteIfExists(temp);sqlite++;}
                    else equal=Files.size(p)==e.getLong("size")&&Util.hash(p).equals(e.getString("hash"));
                }
                if(equal&&e.getString("type").equals("link"))equal=Files.readSymbolicLink(p).toString().equals(e.getString("target"));
                if(equal&&e.getString("type").equals("char"))equal=current.getLong("rdev")==e.getLong("rdev");
                if(current.getInt("mode")!=e.getInt("mode")||current.getInt("uid")!=e.getInt("uid")||current.getInt("gid")!=e.getInt("gid"))metadataChanged++;
                if(equal)same++;else{changed++;if(samples.length()<20)samples.put(Util.obj("root",e.getString("root"),"path",e.getString("path")));}
            }catch(Exception ex){unreadable++;}
        }
        Set<String> expectedPaths=new HashSet<>();for(int i=0;i<entries.length();i++){JSONObject e=entries.getJSONObject(i);expectedPaths.add(e.getString("root")+"/"+e.getString("path"));}
        int added=0,moduleStatesChanged=0;Set<String> snapshotModules=new HashSet<>();
        for(JSONObject r:Restore.roots(manifest)){
            String key=r.getString("key");Path p=sources.get(key);
            if(p!=null&&Files.exists(p,LinkOption.NOFOLLOW_LINKS))try{added+=newEntries(key,r.getString("kind"),p,p,expectedPaths);}catch(Exception ex){unreadable++;}
            if(r.getString("kind").equals("module")){snapshotModules.add(r.getJSONObject("detail").getString("id"));if(p==null||live.removed(p)||live.enabled(p)!=r.getJSONObject("detail").getBoolean("enabled"))moduleStatesChanged++;}
        }
        Set<String> addedModules=new HashSet<>(modules.keySet());addedModules.removeAll(snapshotModules);
        JSONObject expectedLsp=manifest.getJSONObject("lsposed"),actualLsp=live.lsposed();boolean lsSame=expectedLsp.optBoolean("present")==actualLsp.optBoolean("present");
        if(lsSame&&expectedLsp.optBoolean("present")){JSONObject tables=expectedLsp.getJSONObject("tables");Iterator<String> names=tables.keys();while(names.hasNext()){String name=names.next();if(!RealRoundTrip.rows(tables.getJSONArray(name)).equals(RealRoundTrip.rows(actualLsp.getJSONObject("tables").getJSONArray(name))))lsSame=false;}}
        boolean packagesSame=RealRoundTrip.rows(manifest.getJSONArray("packages")).equals(RealRoundTrip.rows(live.packages()));
        JSONObject result=Util.obj("snapshot",manifest.getString("id"),"comparedEntries",entries.length(),"same",same,"changedSinceSnapshot",changed,"missingSinceSnapshot",missing,"newEntriesSinceSnapshot",added,"newModulesSinceSnapshot",addedModules.size(),"moduleStatesChanged",moduleStatesChanged,"lsposedTablesMatch",lsSame,"packageInventoryMatches",packagesSame,"unreadable",unreadable,"sqliteComparedViaConsistentCopy",sqlite,"metadataChanged",metadataChanged,"changedSamples",samples,"skippedUsers",manifest.optJSONArray("skippedUsers"),"sourceIsLive",true,"sourceModifiedByVerifier",false);
        Util.write(engine.state.resolve("comparison.json"),result);result.remove("changedSamples");System.out.println(result);
    }
    public static void main(String[] args)throws Exception{
        Thread.setDefaultUncaughtExceptionHandler((t,x)->{System.err.println(Util.safeError(x));System.exit(1);});
        check(args.length==3&&args[0].matches("[A-Za-z0-9_-]{1,80}")&&args[1].matches("[0-9]+-[a-f0-9]{8}")&&Arrays.asList("compare","restore").contains(args[2]),"参数无效");
        Path base=own().resolve(args[1]),state=base.resolve("state"),target=base.resolve("target");Util.mkdir(state);
        Engine reader=new Engine(state,new Inventory(Paths.get("/"),false),Main.config());JSONObject manifest=reader.manifest(args[0],args[1]);
        if(args[2].equals("compare")){compare(reader,manifest);return;}
        check(!Files.exists(state.resolve("restore.json")),"已有模拟恢复日志，先检查原运行结果");
        Inventory sandbox=new Inventory(target,true){@Override JSONObject environment()throws Exception{return manifest.getJSONObject("environment");}};
        Engine engine=new Engine(state,sandbox,Main.config());Restore restore=new Restore(engine);Util.mkdir(target);
        for(JSONObject r:Restore.roots(manifest))if(r.getString("kind").startsWith("app_"))Util.mkdir(restore.target(r.getString("destination")));
        restore.prepare(manifest);restore.early();restore.finishApps();restore.early();
        check(restore.status().getString("phase").equals("complete"),"模拟恢复未完成");verifyRestored(manifest,restore,target);
    }
}
