package dev.nasbackup;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import android.system.Os;

/** Persistent two-boot restore journal. No reboot command is issued automatically. */
final class Restore {
    final Engine e;final Path state,journal;final Inventory inv;
    Restore(Engine engine){e=engine;state=e.state;inv=e.inventory;journal=state.resolve("restore.json");}
    JSONObject status()throws Exception{return Files.exists(journal)?Util.read(journal):Util.obj("phase","none");}
    static boolean exists(Path p){return Files.exists(p,LinkOption.NOFOLLOW_LINKS);}
    void save(JSONObject j)throws Exception{Util.write(journal,j);UiState.restore(state,j);}
    Path target(String p)throws Exception {
        Path result=inv.path(p);Path c=inv.root;
        for(Path part:inv.root.relativize(result)){c=c.resolve(part);Util.require(!Files.isSymbolicLink(c),"恢复目标经过符号链接: "+p);}
        return result;
    }
    JSONObject prepare(JSONObject m)throws Exception {
        String phase=status().getString("phase");Util.require(phase.equals("none")||phase.equals("rolled-back")||phase.equals("cancelled"),"已有恢复任务；请先处理或清理");
        e.progress("正在检查恢复环境和应用版本");
        JSONObject plan=e.plan(m);Util.require(plan.getJSONArray("blockers").length()==0,plan.getJSONArray("blockers").toString());
        // Updating an existing APK can run migrations; only identical APKs are accepted here.
        JSONArray packages=m.getJSONArray("packages");
        List<Integer> existingUsers=new ArrayList<>();if(!inv.sandbox){Matcher users=Pattern.compile("UserInfo\\{(\\d+):").matcher(Util.run("/system/bin/pm","list","users"));while(users.find())existingUsers.add(Integer.parseInt(users.group(1)));}
        if(!inv.sandbox)for(int i=0;i<packages.length();i++){
            JSONObject p=packages.getJSONObject(i);Set<String> installed=new HashSet<>();for(int user:existingUsers)installed.addAll(apkPaths(p.getString("package"),user));
            if(!installed.isEmpty()){
                Set<String> before=new HashSet<>(),after=new HashSet<>();for(String a:installed)before.add(Util.hash(Paths.get(a)));
                for(JSONObject root:roots(m))if(root.getString("kind").equals("apk")&&samePackage(root.getJSONObject("detail"),p))after.add(rootEntry(m,root.getString("key")).getString("hash"));
                Util.require(before.equals(after),"已安装不同版本的模块 APK，无法无损回退，请先卸载该模块或使用干净环境: "+p.getString("package"));
            }
        }
        Path stage=e.stage(m);
        e.progress("快照已校验，正在准备模块应用");
        JSONObject j=Util.obj("phase","installing","id",m.getString("id"),"boot",boot(),"newPackages",new JSONArray(),"moves",new JSONArray(),"appMoves",new JSONArray(),"uidMap",new JSONObject());save(j);
        try {
            Map<String,String> apkMap=new HashMap<>();Map<Integer,Integer> uidMap=new HashMap<>();
            for(int i=0;i<packages.length();i++){
                JSONObject p=packages.getJSONObject(i);String pkg=p.getString("package");int user=p.getInt("user");
                if(!inv.sandbox&&apkPaths(pkg,user).isEmpty()){
                    // Journal intent before PackageManager changes; rollback can remove interrupted installs.
                    j.getJSONArray("newPackages").put(Util.obj("package",pkg,"user",user));save(j);install(m,p,stage);
                }
                if(inv.sandbox){apkMap.put(pkg,"/data/app/test/"+pkg+"/base.apk");uidMap.put(p.getInt("uid"),p.getInt("uid"));}
                else {List<String> paths=apkPaths(pkg,user);Util.require(!paths.isEmpty(),"APK 安装后不可见");String base=paths.stream().filter(x->x.endsWith("/base.apk")).findFirst().orElse(paths.get(0));apkMap.put(pkg,base);uidMap.put(p.getInt("uid"),uid(pkg,user));}
            }
            JSONObject map=new JSONObject();for(Map.Entry<Integer,Integer> pair:uidMap.entrySet())map.put(pair.getKey().toString(),pair.getValue());j.put("uidMap",map);save(j);
            JSONObject lsp=m.getJSONObject("lsposed");if(lsp.optBoolean("present")){
                Lsp.write(lsp,stage.resolve("lsposed.db"),apkMap);
                JSONObject paused=new JSONObject(lsp.toString());JSONArray enabled=paused.getJSONObject("tables").getJSONArray(paused.getString("variant").equals("modern")?"modules_state":"modules");
                for(int i=0;i<enabled.length();i++)enabled.getJSONObject(i).put("enabled",0);
                Lsp.write(paused,stage.resolve("lsposed-disabled.db"),apkMap);
            }
            if(!m.optString("metamodule").isEmpty())Files.createSymbolicLink(stage.resolve("metamodule"),Paths.get("/data/adb/modules/"+m.getString("metamodule")));
            for(JSONObject root:roots(m)){String kind=root.getString("kind");if(!kind.startsWith("app_")&&!kind.equals("apk")&&!kind.equals("original_zip"))applyMeta(m,root,payload(m,root),uidMap,false);}
            for(JSONObject root:roots(m))if(root.getString("kind").equals("module")&&root.getJSONObject("detail").has("enabled")){
                Path marker=payload(m,root).resolve("disable");if(root.getJSONObject("detail").getBoolean("enabled"))Files.deleteIfExists(marker);else if(!exists(marker))Util.write(marker,new byte[0]);
            }
            j.put("phase","awaiting-reboot");j.put("boot",boot());save(j);e.progress("恢复准备完成，请重启手机");return j;
        }catch(Exception ex){j.put("phase","prepare-failed").put("error",Util.safeError(ex));save(j);throw ex;}
    }
    String boot()throws Exception{return inv.sandbox?"fixture":new String(Files.readAllBytes(Paths.get("/proc/sys/kernel/random/boot_id")),Util.UTF8).trim();}
    static List<JSONObject> roots(JSONObject m)throws Exception{List<JSONObject> out=new ArrayList<>();JSONArray a=m.getJSONArray("roots");for(int i=0;i<a.length();i++)out.add(a.getJSONObject(i));return out;}
    static JSONObject rootEntry(JSONObject m,String key)throws Exception{JSONArray a=m.getJSONArray("entries");for(int i=0;i<a.length();i++){JSONObject v=a.getJSONObject(i);if(v.getString("root").equals(key)&&v.getString("path").isEmpty())return v;}throw new IOException("缺少备份根");}
    static boolean samePackage(JSONObject a,JSONObject b)throws Exception{return a.getString("package").equals(b.getString("package"))&&a.getInt("user")==b.getInt("user");}
    static List<String> apkPaths(String pkg,int user)throws Exception {
        String listing=Util.run("/system/bin/pm","list","packages","--user",Integer.toString(user),pkg);boolean present=false;for(String line:listing.split("\n"))if(line.trim().equals("package:"+pkg))present=true;
        if(!present)return new ArrayList<>();
        String result=Util.run("/system/bin/pm","path","--user",Integer.toString(user),pkg);List<String> paths=new ArrayList<>();for(String line:result.split("\n"))if(line.startsWith("package:"))paths.add(line.substring(8));return paths;
    }
    static int uid(String pkg,int user)throws Exception {
        String result=Util.run("/system/bin/pm","list","packages","-U","--user",Integer.toString(user),pkg);
        Matcher m=Pattern.compile("package:"+Pattern.quote(pkg)+" uid:(\\d+)(?:\\s|$)").matcher(result);Util.require(m.find(),"无法获取新的应用 UID");return Integer.parseInt(m.group(1));
    }
    void install(JSONObject m,JSONObject pkg,Path stage)throws Exception {
        List<Path> files=new ArrayList<>();long size=0;
        for(JSONObject root:roots(m))if(root.getString("kind").equals("apk")&&samePackage(root.getJSONObject("detail"),pkg)){Path p=stage.resolve("roots").resolve(root.getString("key")).resolve("payload");files.add(p);size+=Files.size(p);}
        Util.require(!files.isEmpty(),"缺少模块 APK");
        String result=Util.run("/system/bin/pm","install-create","--user",pkg.getInt("user")+"","--pkg",pkg.getString("package"),"-r","-S",size+"");Matcher id=Pattern.compile("\\[(\\d+)\\]").matcher(result);Util.require(id.find(),"不能建立 APK 安装会话");String session=id.group(1);
        try {for(int i=0;i<files.size();i++){Path p=files.get(i);result=Util.run("/system/bin/pm","install-write","-S",Files.size(p)+"",session,"part"+i+".apk",p.toString());Util.require(result.contains("Success"),"APK 分片写入失败");}
            result=Util.run("/system/bin/pm","install-commit",session);Util.require(result.contains("Success"),"APK 安装失败");
        }catch(Exception x){try{Util.run("/system/bin/pm","install-abandon",session);}catch(Exception ignored){}throw x;}
    }
    Map<Integer,Integer> uids(JSONObject j)throws Exception {Map<Integer,Integer> out=new HashMap<>();JSONObject map=j.getJSONObject("uidMap");Iterator<String> keys=map.keys();while(keys.hasNext()){String key=keys.next();out.put(Integer.parseInt(key),map.getInt(key));}return out;}
    Path payload(JSONObject m,JSONObject root)throws Exception {Path p=state.resolve("restore-stage/roots").resolve(root.getString("key"));return rootEntry(m,root.getString("key")).getString("type").equals("dir")?p:p.resolve("payload");}
    void applyMeta(JSONObject m,JSONObject root,Path p,Map<Integer,Integer> uids,boolean skipRoot)throws Exception {
        JSONArray entries=m.getJSONArray("entries");for(int i=entries.length()-1;i>=0;i--){JSONObject item=entries.getJSONObject(i);if(item.getString("root").equals(root.getString("key"))&&(!skipRoot||!item.getString("path").isEmpty()))Engine.metadata(p,item,uids);}
    }
    void move(JSONObject j,Path from,Path to,String key)throws Exception {
        Path old=state.resolve("rollback").resolve(key);Util.mkdir(old.getParent());
        JSONObject op=Util.obj("target",to.toString(),"old",old.toString(),"source",from.toString(),"hadOld",exists(to));j.getJSONArray("moves").put(op);save(j);
        if(exists(to))Files.move(to,old,StandardCopyOption.ATOMIC_MOVE);
        Util.mkdir(to.getParent());Files.move(from,to,StandardCopyOption.ATOMIC_MOVE);
    }
    void early()throws Exception {
        JSONObject j=status();String phase=j.getString("phase");
        if(phase.equals("rollback-requested")||phase.equals("applying")||phase.equals("final-applying")||phase.equals("rollback-pausing")){rollbackEarly(j);return;}
        if(phase.equals("rollback-final-reboot")){restoreRollbackDb(j);return;}
        if(phase.equals("awaiting-final-reboot")){
            Util.require(inv.sandbox||!j.getString("boot").equals(boot()),"需要再次重启以启用 LSPosed 模块");
            j.put("phase","final-applying");save(j);
            try{Path db=state.resolve("restore-stage/lsposed.db");if(exists(db))replaceDb(j,db,"lsdb-final");j.put("phase","complete");save(j);}
            catch(Exception x){j.put("error",Util.safeError(x));save(j);rollbackEarly(j);throw x;}
            return;
        }
        if(!phase.equals("awaiting-reboot"))return;
        Util.require(inv.sandbox||!j.getString("boot").equals(boot()),"需要重启后应用系统配置");
        JSONObject m=Util.read(state.resolve("restore-stage/manifest.json"));Engine.validate(m);j.put("phase","applying");save(j);
        try {
            for(JSONObject r:roots(m)){
                String kind=r.getString("kind");if(kind.equals("apk")||kind.equals("original_zip")||kind.startsWith("app_"))continue;
                Path p=payload(m,r),to=target(r.getString("destination"));move(j,p,to,r.getString("key"));
            }
            if(!m.optString("metamodule").isEmpty()){
                Path entry=inv.path("/data/adb/metamodule");Util.require(!exists(entry)||Files.isSymbolicLink(entry),"挂载引擎入口不是符号链接，停止恢复");
                move(j,state.resolve("restore-stage/metamodule"),entry,"metamodule-entry");
            }
            if(m.getJSONObject("lsposed").optBoolean("present")){
                replaceDb(j,state.resolve("restore-stage/lsposed-disabled.db"),"lsdb");
            }
            j.put("phase","awaiting-unlock").put("appliedBoot",boot());save(j);
        }catch(Exception x){j.put("error",Util.safeError(x));save(j);rollbackEarly(j);throw x;}
    }
    void replaceDb(JSONObject j,Path source,String key)throws Exception {
        Path db=target("/data/adb/lspd/config/modules_config.db");
        // Preserve main DB and WAL together before replacement while LSPosed is offline.
        for(String suffix:Arrays.asList("-wal","-shm","-journal")){Path side=db.resolveSibling(db.getFileName()+suffix);if(exists(side)){Path empty=state.resolve("restore-stage/empty-"+key+suffix);Files.write(empty,new byte[0]);move(j,empty,side,key+suffix);Files.delete(side);}}
        move(j,source,db,key);Os.chmod(db.toString(),0600);if(!inv.sandbox)Util.run("/system/bin/restorecon","-F",db.toString());
    }
    void finishApps()throws Exception {
        JSONObject j=status();if(!j.getString("phase").equals("awaiting-unlock"))return;
        JSONObject m=Util.read(state.resolve("restore-stage/manifest.json"));
        for(JSONObject r:roots(m)){
            String kind=r.getString("kind");if(!kind.startsWith("app_"))continue;JSONObject detail=r.getJSONObject("detail");String pkg=detail.getString("package");int user=detail.getInt("user");
            if(!inv.sandbox){if(!Util.unlocked(user))return;Util.run("/system/bin/am","force-stop","--user",user+"",pkg);}
            String key=r.getString("key");Path to=target(r.getString("destination"));Path saved=state.resolve("rollback-apps").resolve(key);
            JSONObject appOp=null;JSONArray operations=j.getJSONArray("appMoves");for(int n=0;n<operations.length();n++)if(operations.getJSONObject(n).getString("key").equals(key))appOp=operations.getJSONObject(n);
            if(appOp!=null&&appOp.optBoolean("done"))continue;
            if(appOp==null){boolean hadRoot=Files.isDirectory(to);if(!hadRoot&&kind.equals("app_external")){Util.mkdir(to);Engine.metadata(to,rootEntry(m,key),uids(j));}
                Util.require(Files.isDirectory(to),"模块应用的加密数据目录尚未建立: "+pkg);copyTree(to,saved);appOp=Util.obj("key",key,"target",to.toString(),"saved",saved.toString(),"hadRoot",hadRoot,"done",false);operations.put(appOp);save(j);}
            // Preserve the Android-created FBE directory inode, owner, and encryption policy.
            clearAppChildren(to);copyAppChildren(payload(m,r),to);applyMeta(m,r,to,uids(j),true);
            if(!inv.sandbox)Util.run("/system/bin/restorecon","-RF",to.toString());appOp.put("done",true);save(j);
        }
        j.put("phase","awaiting-final-reboot").put("boot",boot());save(j);
    }
    void requestRollback()throws Exception {JSONObject j=status();Util.require(!j.getString("phase").equals("none"),"没有可回退的恢复任务");j.put("phase","rollback-requested");save(j);}
    void rollbackEarly(JSONObject j)throws Exception {
        JSONArray ops=j.getJSONArray("moves");for(int i=ops.length()-1;i>=0;i--){JSONObject op=ops.getJSONObject(i);if(op.optBoolean("rolledBack"))continue;Path to=Paths.get(op.getString("target")),old=Paths.get(op.getString("old")),source=Paths.get(op.getString("source"));
            if(exists(old)){Util.delete(to);Util.mkdir(to.getParent());Files.move(old,to,StandardCopyOption.ATOMIC_MOVE);}
            else if(!op.getBoolean("hadOld")&&!exists(source))Util.delete(to);
            op.put("rolledBack",true);save(j);
        }
        j.put("phase","rollback-pausing");save(j);pauseRollbackDb(j);j.put("phase","rollback-unlock");save(j);
    }
    void pauseRollbackDb(JSONObject j)throws Exception {
        Path db=target("/data/adb/lspd/config/modules_config.db"),saved=state.resolve("rollback-lsp");
        if(!Files.exists(db)&&!Files.exists(saved.resolve("modules_config.db")))return;
        if(!j.optBoolean("rollbackDbPrepared")){
            Util.mkdir(saved);JSONObject snapshot=Lsp.read(db);Map<String,String> paths=new HashMap<>();JSONArray modules=snapshot.getJSONObject("tables").getJSONArray("modules");
            for(int i=0;i<modules.length();i++){JSONObject row=modules.getJSONObject(i);paths.put(row.getString("module_pkg_name"),row.getString("apk_path"));}
            JSONArray states=snapshot.getJSONObject("tables").getJSONArray(snapshot.getString("variant").equals("modern")?"modules_state":"modules");for(int i=0;i<states.length();i++)states.getJSONObject(i).put("enabled",0);
            Path paused=saved.resolve("paused.db");Files.deleteIfExists(paused);Lsp.write(snapshot,paused,paths);
            for(String suffix:Arrays.asList("","-wal","-shm","-journal"))j.put("rollbackDbHad"+suffix,exists(db.resolveSibling(db.getFileName()+suffix)));
            j.put("rollbackDbPrepared",true);save(j);
        }
        // Every move is recoverable by presence; a crash can resume before LSPosed starts.
        for(String suffix:Arrays.asList("","-wal","-shm","-journal")){Path from=db.resolveSibling(db.getFileName()+suffix),to=saved.resolve("modules_config.db"+suffix);if(!exists(to)&&exists(from))Files.move(from,to,StandardCopyOption.ATOMIC_MOVE);}
        Path paused=saved.resolve("paused.db");if(exists(paused))Files.move(paused,db,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        if(!inv.sandbox)Util.run("/system/bin/restorecon","-F",db.toString());
    }
    void restoreRollbackDb(JSONObject j)throws Exception {
        Path db=target("/data/adb/lspd/config/modules_config.db"),saved=state.resolve("rollback-lsp");
        for(String suffix:Arrays.asList("-wal","-shm","-journal","")){Path from=saved.resolve("modules_config.db"+suffix),to=db.resolveSibling(db.getFileName()+suffix);
            String marker="rollbackDbDone"+suffix;if(j.optBoolean(marker))continue;
            if(exists(from)){Util.delete(to);Files.move(from,to,StandardCopyOption.ATOMIC_MOVE);}else if(!suffix.isEmpty()&&!j.optBoolean("rollbackDbHad"+suffix))Files.deleteIfExists(to);j.put(marker,true);save(j);
        }j.put("phase","rolled-back");save(j);
    }
    void rollbackApps()throws Exception {
        JSONObject j=status();if(!j.getString("phase").equals("rollback-unlock"))return;
        JSONArray apps=j.getJSONArray("appMoves");for(int i=apps.length()-1;i>=0;i--){JSONObject op=apps.getJSONObject(i);if(op.optBoolean("rolledBack"))continue;Path to=Paths.get(op.getString("target"));
            if(!inv.sandbox){String pkg=to.getFileName().toString();String[] parts=to.toString().split("/");String user=parts[3];if(!Util.unlocked(Integer.parseInt(user)))return;Util.run("/system/bin/am","force-stop","--user",user,pkg);}
            if(!op.optBoolean("hadRoot",true))Util.delete(to);else{clearAppChildren(to);copyAppChildren(Paths.get(op.getString("saved")),to);if(!inv.sandbox)Util.run("/system/bin/restorecon","-RF",to.toString());}op.put("rolledBack",true);save(j);
        }
        JSONArray pkgs=j.getJSONArray("newPackages");for(int i=0;i<pkgs.length();i++){JSONObject p=pkgs.getJSONObject(i);if(!inv.sandbox&&!p.optBoolean("removed")){String result=Util.run("/system/bin/pm","uninstall","--user",p.getInt("user")+"",p.getString("package"));Util.require(result.contains("Success")||apkPaths(p.getString("package"),p.getInt("user")).isEmpty(),"回退卸载失败");p.put("removed",true);save(j);}}
        j.put("phase",j.optBoolean("rollbackDbPrepared")?"rollback-final-reboot":"rolled-back");save(j);
    }
    void clean()throws Exception {
        String p=status().getString("phase");Util.require(Arrays.asList("none","complete","rolled-back","cancelled").contains(p),"恢复进行中，不能清理");
        for(String name:Arrays.asList("restore-stage","rollback","rollback-apps","rollback-lsp"))Util.delete(state.resolve(name));Files.deleteIfExists(journal);
    }
    static boolean appCache(Path p){return Arrays.asList("cache","code_cache").contains(p.getFileName().toString());}
    static void clearAppChildren(Path p)throws Exception{
        try(DirectoryStream<Path> d=Files.newDirectoryStream(p)){for(Path c:d){
            // Android stores cache inode numbers on the app root; do not let restored data reuse them.
            if(appCache(c)&&Files.isDirectory(c,LinkOption.NOFOLLOW_LINKS))try(DirectoryStream<Path> cache=Files.newDirectoryStream(c)){for(Path child:cache)Util.delete(child);}
            else Util.delete(c);
        }}
    }
    static void copyAppChildren(Path from,Path to)throws Exception{try(DirectoryStream<Path> d=Files.newDirectoryStream(from)){for(Path p:d)if(!appCache(p))copyTree(p,to.resolve(p.getFileName()));}}
    static void copyChildren(Path from,Path to)throws Exception{try(DirectoryStream<Path>d=Files.newDirectoryStream(from)){for(Path p:d)copyTree(p,to.resolve(p.getFileName()));}}
    static void copyTree(Path from,Path to)throws Exception {
        JSONObject meta=Inventory.meta(from);String type=meta.getString("type");
        if(type.equals("dir")){Util.mkdir(to);copyChildren(from,to);}else if(type.equals("link")){Files.createSymbolicLink(to,Paths.get(meta.getString("target")));Os.lchown(to.toString(),meta.getInt("uid"),meta.getInt("gid"));return;}
        else if(type.equals("file"))Files.copy(from,to,StandardCopyOption.REPLACE_EXISTING);
        else if(type.equals("socket"))return;
        else if(type.equals("fifo")||type.equals("whiteout")||type.equals("char"))Util.node(to,type,type.equals("char")?meta.getLong("rdev"):0);
        else throw new IOException("回退数据中存在不支持的特殊文件");
        Engine.metadata(to,meta.put("path",""),Collections.emptyMap());
    }
}
