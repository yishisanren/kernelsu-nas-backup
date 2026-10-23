package dev.nasbackup;

import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.zip.*;
import android.database.sqlite.SQLiteDatabase;
import android.database.DatabaseUtils;
import android.system.*;

final class Engine {
    static final int CHUNK_SIZE=8*1024*1024;
    final Path state;final Inventory inventory;final JSONObject config;final Dav dav;
    final JSONArray roots=new JSONArray(),entries=new JSONArray(),warnings=new JSONArray();
    Packs packs;
    String currentPhase="";long lastProgress;
    Engine(Path state,Inventory inventory,JSONObject config)throws Exception{this(state,inventory,config,true);}
    Engine(Path state,Inventory inventory,JSONObject config,boolean network)throws Exception{this.state=state;this.inventory=inventory;this.config=config;this.dav=network?new Dav(config):null;}
    String vault()throws Exception{
        if(config.optString("vault").matches("[a-zA-Z0-9_-]{1,80}"))return config.getString("vault");
        String identity=inventory.sandbox?"fixture":Util.prop("ro.boot.serialno")+Util.prop("ro.product.device");
        Util.require(!identity.isEmpty(),"无法生成设备备份标识");return "device-"+Util.hash(identity.getBytes(Util.UTF8)).substring(0,20);
    }
    void progress(String phase)throws Exception{currentPhase=phase;lastProgress=System.currentTimeMillis();Util.write(state.resolve("status.json"),Util.obj("phase",phase,"time",lastProgress,"uploadedFiles",dav.uploadedObjects,"uploadedBytes",dav.uploadedBytes));}
    void addRoot(String key,String kind,String destination,Path source,JSONObject detail)throws Exception{
        JSONObject r=Util.obj("key",key,"kind",kind,"destination",destination,"detail",detail);
        validateRoot(r);Util.require(!Files.isSymbolicLink(source),"配置根目录不能是链接: "+destination);
        roots.put(r);progress("读取 "+key);capture(key,source,source,kind);
    }
    static boolean cachePath(Path base,Path p,String kind){
        if(p.equals(base))return false;String first=base.relativize(p).getName(0).toString();
        if(kind.startsWith("app_"))return Arrays.asList("cache","code_cache","lib").contains(first);
        return kind.equals("module")&&Arrays.asList("oat",".git").contains(first);
    }
    static boolean sqlite(Path p)throws Exception{
        if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)<16)return false;
        byte[] b=new byte[16];try(InputStream in=Files.newInputStream(p)){if(in.read(b)!=16)return false;}return Arrays.equals(b,"SQLite format 3\0".getBytes(Util.UTF8));
    }
    void capture(String key,Path base,Path p,String kind)throws Exception{
        if(cachePath(base,p,kind))return;
        String name=p.getFileName().toString();
        if(kind.equals("module")&&(name.equals("update")||name.equals("remove")||name.equals(".hot_install")))return;
        for(String suffix:Arrays.asList("-wal","-shm","-journal"))if(name.endsWith(suffix)&&sqlite(p.resolveSibling(name.substring(0,name.length()-suffix.length()))))return;
        JSONObject e=Inventory.meta(p);e.put("root",key).put("path",base.equals(p)?"":base.relativize(p).toString());
        String type=e.getString("type");if(type.equals("socket")){warnings.put("跳过运行时 socket: "+key+"/"+e.getString("path"));return;}Util.require(!type.equals("unsupported"),"不支持的文件类型: "+key+"/"+e.getString("path"));
        if(type.equals("char"))Util.require(safeDevice(e.getLong("rdev")),"不支持备份此设备节点");
        if(type.equals("file")){
            Path spool=state.resolve("spool/object");Util.mkdir(spool.getParent());
            try{
                if(sqlite(p)){
                    Files.deleteIfExists(spool);SQLiteDatabase db=Db.open(p,false);
                    try{db.execSQL("VACUUM INTO "+DatabaseUtils.sqlEscapeString(spool.toString()));}finally{db.close();}
                }else{
                    BasicFileAttributes before=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                    try(InputStream in=Files.newInputStream(p,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);OutputStream out=Files.newOutputStream(spool)){byte[] b=new byte[262144];int count;while((count=in.read(b))!=-1)out.write(b,0,count);}
                    BasicFileAttributes after=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                    Util.require(before.isRegularFile()&&after.isRegularFile()&&before.size()==after.size()&&before.lastModifiedTime().equals(after.lastModifiedTime())&&Objects.equals(before.fileKey(),after.fileKey()),"备份中源文件变化，将稍后重试: "+key);
                }
                String hash=Util.hash(spool);long size=Files.size(spool);e.put("hash",hash).put("size",size);
                if(packs!=null){if(size>CHUNK_SIZE)captureChunks(e,spool);else packs.add(spool,hash,size);}
                else if(size>32L*1024*1024&&!dav.hasObject(hash,size))captureChunks(e,spool);else dav.queueObject(spool,hash,size);
                if(System.currentTimeMillis()-lastProgress>=1000)progress(currentPhase);
            }finally{Files.deleteIfExists(spool);}
        }
        entries.put(e);Util.require(entries.length()<=250000,"快照文件数量超限");
        if(type.equals("dir")){
            List<Path> children=new ArrayList<>();try(DirectoryStream<Path>d=Files.newDirectoryStream(p)){for(Path c:d)children.add(c);}children.sort(Comparator.comparing(Path::toString));for(Path c:children)capture(key,base,c,kind);
        }
    }
    JSONObject backup()throws Exception{
        try{return backupContents();}finally{try{if(packs!=null)packs.close();}finally{dav.closeUploads();}}
    }
    void captureChunks(JSONObject entry,Path file)throws Exception {
        JSONArray chunks=new JSONArray();Path part=file.resolveSibling("chunk");
        try(InputStream in=Files.newInputStream(file)){
            byte[] buffer=new byte[262144];boolean ended=false;
            while(!ended){int size=0;
                try(OutputStream out=Files.newOutputStream(part)){
                    while(size<CHUNK_SIZE){int n=in.read(buffer,0,Math.min(buffer.length,CHUNK_SIZE-size));if(n<0){ended=true;break;}out.write(buffer,0,n);size+=n;}
                }
                if(size>0){String hash=Util.hash(part);chunks.put(Util.obj("hash",hash,"size",size));if(packs!=null)packs.add(part,hash,size);else dav.queueObject(part,hash,size);if(System.currentTimeMillis()-lastProgress>=1000)progress(currentPhase);}
            }
        }finally{Files.deleteIfExists(part);}
        entry.put("chunks",chunks);
    }
    static JSONArray objects(JSONObject entry)throws Exception {
        return entry.has("chunks")?entry.getJSONArray("chunks"):new JSONArray().put(Util.obj("hash",entry.getString("hash"),"size",entry.getLong("size")));
    }
    JSONObject backupContents()throws Exception{
        Util.mkdir(state);Util.delete(state.resolve("spool"));String vault=vault();dav.dirs("vaults/"+vault+"/snapshots");dav.dirs("objects");
        if(!config.optBoolean("legacyObjects"))packs=new Packs(this);
        String initial=inventory.quick();List<Path> mods=inventory.modules();JSONObject lsp=inventory.lsposed();JSONArray packages=inventory.packages();
        inventory.warnSkipped(warnings);lsp.put("skippedUsers",inventory.skippedUsers);
        Lsp.warnMissing(lsp,packages,warnings);
        if(!inventory.sandbox){Set<Integer> users=new HashSet<>();for(int i=0;i<packages.length();i++)users.add(packages.getJSONObject(i).getInt("user"));for(int user:users)Util.require(Util.unlocked(user),"请先解锁 Android 用户 "+user+"，避免遗漏加密配置");}
        for(Path p:mods){String id=p.getFileName().toString();if(inventory.removed(p))continue;
            addRoot("module-"+id,"module","/data/adb/modules/"+id,p,Util.obj("id",id,"version",Inventory.props(p.resolve("module.prop")).getProperty("version",""),"enabled",inventory.enabled(p)));
        }
        int n=0;for(String path:inventory.external(mods,config,warnings))addRoot("extra-"+(n++),"extra",path,inventory.path(path),new JSONObject());
        Path ksu=inventory.path("/data/adb/ksu/module_configs");if(Files.isDirectory(ksu))addRoot("ksu-config","ksu_config","/data/adb/ksu/module_configs",ksu,new JSONObject());
        Path lspfiles=inventory.path("/data/adb/lspd/modules");if(Files.isDirectory(lspfiles))addRoot("lspd-files","lspd_files","/data/adb/lspd/modules",lspfiles,new JSONObject());
        String misc=Lsp.misc(lsp);if(!misc.isEmpty()&&Files.isDirectory(inventory.path(misc)))addRoot("lspd-misc","lspd_misc",misc,inventory.path(misc),new JSONObject());
        for(int i=0;i<packages.length();i++){
            JSONObject pkg=packages.getJSONObject(i);String name=pkg.getString("package");int user=pkg.getInt("user");JSONArray apks=pkg.getJSONArray("apks");
            for(int j=0;j<apks.length();j++)addRoot("apk-"+i+"-"+j,"apk",apks.getString(j),inventory.path(apks.getString(j)),Util.obj("package",name,"user",user,"part",j,"uid",pkg.getInt("uid")));
            for(String kind:Arrays.asList("app_ce","app_de","app_external")){
                String target=kind.equals("app_ce")?"/data/user/"+user+"/"+name:kind.equals("app_de")?"/data/user_de/"+user+"/"+name:"/data/media/"+user+"/Android/data/"+name;
                Path p=inventory.path(target);if(Files.exists(p))addRoot(kind+"-"+i,kind,target,p,Util.obj("package",name,"user",user,"uid",pkg.getInt("uid")));
            }
        }
        JSONArray zipDirs=config.optJSONArray("zipDirs");Set<String> zipModules=new HashSet<>();
        if(zipDirs!=null)for(int i=0;i<zipDirs.length();i++){
            String d=zipDirs.getString(i);Util.require(d.startsWith("/storage/emulated/0/")||d.startsWith("/data/media/0/"),"安装包目录必须位于主用户共享存储");
            Path dir=inventory.path(d);Util.require(Files.isDirectory(dir),"安装包目录不可读: "+d);
            try(DirectoryStream<Path> stream=Files.newDirectoryStream(dir,"*.zip")){for(Path zip:stream){if(Files.isSymbolicLink(zip))continue;
                try(ZipFile z=new ZipFile(zip.toFile())){ZipEntry prop=z.getEntry("module.prop");if(prop==null)continue;Properties pr=new Properties();try(Reader in=new InputStreamReader(z.getInputStream(prop),Util.UTF8)){pr.load(in);}String id=pr.getProperty("id","");
                    for(Path mod:mods)if(mod.getFileName().toString().equals(id)&&Inventory.props(mod.resolve("module.prop")).getProperty("versionCode","").equals(pr.getProperty("versionCode","missing"))){
                        String key="zip-"+id+"-"+Util.hash(zip.toString().getBytes(Util.UTF8)).substring(0,8);addRoot(key,"original_zip",zip.toString(),zip,Util.obj("id",id));zipModules.add(id);
                    }
                }catch(ZipException ignored){}
            }}
        }
        for(Path p:mods)if(!zipModules.contains(p.getFileName().toString()))warnings.put(p.getFileName()+": 未找到同版本原 ZIP，使用目录快照。");
        dav.finishObjects();
        Util.require(initial.equals(inventory.quick()),"备份期间模块安装或 LSPosed 状态发生变化，稍后重试");
        String id=Long.toString(System.currentTimeMillis())+"-"+UUID.randomUUID().toString().substring(0,8);
        JSONObject manifest=Util.obj("format",packs==null?2:3,"id",id,"vault",vault,"created",System.currentTimeMillis(),"tool",Util.VERSION,"environment",inventory.environment(),"roots",roots,"entries",entries,"lsposed",lsp,"packages",packages,"skippedUsers",inventory.skippedUsers,"warnings",warnings);
        if(packs!=null)packs.manifest(manifest);
        String mount=inventory.metamodule();if(!mount.isEmpty()&&Files.exists(inventory.path("/data/adb/modules/"+mount+"/remove")))mount="";
        manifest.put("metamodule",mount);validate(manifest);
        // Publish the full logical inventory only after all incremental objects are durable and verified.
        byte[] bytes=Util.bytes(manifest);Util.require(bytes.length<=64*1024*1024,"快照清单超出大小限制");dav.put("vaults/"+vault+"/snapshots/"+id+".json",bytes);
        Util.require(Util.hash(dav.get("vaults/"+vault+"/snapshots/"+id+".json",64*1024*1024)).equals(Util.hash(bytes)),"NAS 清单回读校验失败");
        dav.put("vaults/"+vault+"/device.json",Util.bytes(inventory.environment()));
        dav.put("vaults/"+vault+"/snapshots/"+id+".sha256",Util.hash(bytes).getBytes(Util.UTF8));
        JSONObject result=Util.obj("phase","备份完成","id",id,"vault",vault,"files",entries.length(),"uploadedFiles",dav.uploadedObjects,"uploadedBytes",dav.uploadedBytes,"skippedUsers",inventory.skippedUsers,"warnings",warnings,"time",System.currentTimeMillis());
        Util.write(state.resolve("last-backup.json"),result);Util.write(state.resolve("status.json"),result);return result;
    }
    JSONObject manifest(String vault,String id)throws Exception{
        Util.require(vault.matches("[a-zA-Z0-9_-]{1,80}")&&id.matches("[0-9]+-[a-f0-9]{8}"),"备份标识无效");
        String p="vaults/"+vault+"/snapshots/"+id;byte[] b=dav.get(p+".json",64*1024*1024);String sha=new String(dav.get(p+".sha256",100),Util.UTF8).trim();Util.require(Util.hash(b).equals(sha),"恢复清单校验失败");
        JSONObject m=new JSONObject(new String(b,Util.UTF8));validate(m);Util.require(m.getString("vault").equals(vault)&&m.getString("id").equals(id),"备份清单身份不匹配");return m;
    }
    JSONObject export(JSONObject m)throws Exception {
        validate(m);Util.require(m.getString("id").matches("[0-9]+-[a-f0-9]{8}")&&m.getString("vault").matches("[a-zA-Z0-9_-]{1,80}"),"导出快照标识无效");
        Path temp=state.resolve("export-stage"),output=state.resolve("exports").resolve(m.getString("id")+".zip"),partial=output.resolveSibling(output.getFileName()+".part");
        Util.delete(temp);Util.mkdir(temp.resolve("objects"));Util.mkdir(output.getParent());progress("正在导出快照压缩包");
        try{
            Packs.download(this,m,temp);
            try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(partial))){
                zip.setLevel(Deflater.NO_COMPRESSION);String prefix="vaults/"+m.getString("vault")+"/snapshots/"+m.getString("id");byte[] manifest=Util.bytes(m);
                for(String suffix:Arrays.asList(".json",".sha256")){zip.putNextEntry(new ZipEntry(prefix+suffix));zip.write(suffix.equals(".json")?manifest:Util.hash(manifest).getBytes(Util.UTF8));zip.closeEntry();}
                Path folder=temp.resolve(m.getInt("format")==3?"packs":"objects");
                try(DirectoryStream<Path> files=Files.newDirectoryStream(folder)){for(Path file:files){String name=file.getFileName().toString();zip.putNextEntry(new ZipEntry(m.getInt("format")==3?"packs/"+name:dav.objectPath(name)));Files.copy(file,zip);zip.closeEntry();}}
            }
            Files.move(partial,output,StandardCopyOption.REPLACE_EXISTING);JSONObject result=Util.obj("phase","导出完成","path",output.toString(),"bytes",Files.size(output),"sha256",Util.hash(output));Util.write(state.resolve("status.json"),result);return result;
        }finally{Files.deleteIfExists(partial);Util.delete(temp);}
    }
    static void validateRoot(JSONObject r)throws Exception{
        Util.require(r.getString("key").matches("[A-Za-z0-9_-]{1,160}"),"备份根标识无效");String kind=r.getString("kind"),p=r.getString("destination");JSONObject d=r.getJSONObject("detail");
        if(kind.equals("module")){String id=d.getString("id");Inventory.validId(id);Util.require(!id.equals(Util.ID)&&p.equals("/data/adb/modules/"+id),"模块目标路径无效");}
        else if(kind.equals("extra"))Inventory.validateExtra(p);
        else if(kind.equals("ksu_config"))Util.require(p.equals("/data/adb/ksu/module_configs"),"KernelSU 配置路径无效");
        else if(kind.equals("lspd_files"))Util.require(p.equals("/data/adb/lspd/modules"),"LSPosed 文件路径无效");
        else if(kind.equals("lspd_misc"))Util.require(Lsp.validMisc(p),"LSPosed misc 路径无效");
        else if(kind.equals("apk")){Inventory.validPackage(d.getString("package"));Util.require(p.startsWith("/data/app/")&&p.endsWith(".apk")&&!p.contains(".."),"APK 源路径无效");}
        else if(kind.equals("original_zip")){Inventory.validId(d.getString("id"));Util.require(p.endsWith(".zip")&&!p.contains(".."),"原安装包路径无效");}
        else if(kind.startsWith("app_")){String pkg=d.getString("package");Inventory.validPackage(pkg);int u=d.getInt("user");Util.require(u>=0&&u<10000,"Android 用户无效");String base=kind.equals("app_ce")?"/data/user/":kind.equals("app_de")?"/data/user_de/":kind.equals("app_external")?"/data/media/":null;
            Util.require(base!=null&&p.equals(base+u+(kind.equals("app_external")?"/Android/data/":"/")+pkg),"应用数据目标路径无效");
        }else throw new IOException("未知备份根类型");
    }
    static void validate(JSONObject m)throws Exception{
        Util.require(m.getInt("format")>=1&&m.getInt("format")<=3,"不支持的备份格式");JSONArray rs=m.getJSONArray("roots"),es=m.getJSONArray("entries");Util.require(rs.length()<10000&&es.length()<=250000,"恢复清单超限");
        Map<String,Long> objectSizes=new HashMap<>();
        Set<String> keys=new HashSet<>(),paths=new HashSet<>(),leaves=new HashSet<>(),destinations=new HashSet<>();
        for(int i=0;i<rs.length();i++){JSONObject r=rs.getJSONObject(i);validateRoot(r);Util.require(keys.add(r.getString("key")),"重复备份根");if(!r.getString("kind").equals("apk"))Util.require(destinations.add(r.getString("destination")),"重复恢复目标");}
        for(String a:destinations)for(String b:destinations)if(!a.equals(b))Util.require(!a.startsWith(b+"/"),"恢复目标互相嵌套");
        for(int i=0;i<es.length();i++){JSONObject e=es.getJSONObject(i);String key=e.getString("root"),p=e.getString("path"),t=e.getString("type");Util.require(keys.contains(key),"未知文件所属根");
            if(!p.isEmpty())Util.beneath(Paths.get("/virtual-root"),p);
            Util.require(Arrays.asList("file","dir","link","whiteout","char","fifo").contains(t),"未知文件类型");Util.require(paths.add(key+"/"+p),"重复文件路径");
            if(t.equals("char"))Util.require(safeDevice(e.getLong("rdev")),"禁止恢复敏感设备节点");
            if(!t.equals("dir"))leaves.add(key+"/"+p);
            Util.require(e.getInt("mode")>=0&&e.getInt("mode")<=07777,"无效文件权限");
            Util.require(e.getInt("uid")>=0&&e.getInt("gid")>=0,"无效文件所有者");
            if(t.equals("file"))Util.require(e.getString("hash").matches("[0-9a-f]{64}")&&e.getLong("size")>=0&&e.getLong("size")<=32L*1024*1024*1024,"无效文件对象");
            if(e.has("chunks")){
                Util.require(m.getInt("format")>=2&&t.equals("file"),"分块仅用于新版清单中的普通文件");JSONArray chunks=e.getJSONArray("chunks");Util.require(chunks.length()>0&&chunks.length()<=4096,"分块数量无效");long total=0;
                for(int n=0;n<chunks.length();n++){JSONObject chunk=chunks.getJSONObject(n);Util.require(chunk.getString("hash").matches("[0-9a-f]{64}")&&chunk.getLong("size")>0&&chunk.getLong("size")<=CHUNK_SIZE,"分块内容无效");total+=chunk.getLong("size");}
                Util.require(total==e.getLong("size"),"分块总大小与文件不符");
            }
            if(t.equals("file")){JSONArray objects=objects(e);for(int n=0;n<objects.length();n++){JSONObject object=objects.getJSONObject(n);String hash=object.getString("hash");long size=object.getLong("size");Long previous=objectSizes.put(hash,size);Util.require(previous==null||previous==size,"同内容对象大小不一致");}}
            if(t.equals("link"))Util.require(e.getString("target").indexOf('\0')<0,"无效链接");
        }
        for(String p:paths){for(int at=p.lastIndexOf('/');at>0;at=p.lastIndexOf('/',at-1))Util.require(!leaves.contains(p.substring(0,at)),"文件/链接不能包含子路径");}
        if(m.getInt("format")==3)Packs.validate(m,objectSizes);
        for(String key:keys)Util.require(paths.contains(key+"/"),"备份根缺少目录或文件记录");
        String metamodule=m.optString("metamodule");if(!metamodule.isEmpty()){Inventory.validId(metamodule);Util.require(keys.contains("module-"+metamodule),"挂载引擎不在模块快照中");}
        Set<String> identities=new HashSet<>();JSONArray packages=m.getJSONArray("packages");for(int i=0;i<packages.length();i++){JSONObject pkg=packages.getJSONObject(i);Inventory.validPackage(pkg.getString("package"));int user=pkg.getInt("user");Util.require(user>=0&&user<10000&&pkg.getInt("uid")>=10000,"无效模块应用身份");Util.require(identities.add(pkg.getString("package")+":"+user),"重复模块应用身份");boolean hasApk=false;for(int n=0;n<rs.length();n++){JSONObject r=rs.getJSONObject(n);if(r.getString("kind").equals("apk")&&Restore.samePackage(r.getJSONObject("detail"),pkg))hasApk=true;}Util.require(hasApk,"模块应用缺少 APK 记录");}
    }
    JSONObject plan(JSONObject m)throws Exception{
        validate(m);JSONArray blockers=new JSONArray();JSONObject a=m.getJSONObject("environment"),b=inventory.environment();
        JSONArray planWarnings=new JSONArray(m.getJSONArray("warnings").toString());Lsp.warnMissing(m.getJSONObject("lsposed"),m.getJSONArray("packages"),planWarnings);
        for(String key:Arrays.asList("device","api","abi","fingerprint","ksu"))if(!a.optString(key).equals(b.optString(key)))blockers.put("环境不一致: "+key);
        if(!b.optString("lsposed").isEmpty()&&!a.optString("lsposed").equals(b.optString("lsposed")))blockers.put("LSPosed 版本不一致");
        if(!inventory.sandbox){String users=Util.run("/system/bin/pm","list","users");JSONArray pkgs=m.getJSONArray("packages");for(int i=0;i<pkgs.length();i++)if(!users.contains("UserInfo{"+pkgs.getJSONObject(i).getInt("user")+":"))blockers.put("请先建立 Android 用户 "+pkgs.getJSONObject(i).getInt("user"));}
        long bytes=0,assembly=0;Set<String> seen=new HashSet<>(),assembled=new HashSet<>();JSONArray es=m.getJSONArray("entries");for(int i=0;i<es.length();i++){JSONObject e=es.getJSONObject(i);if(!e.getString("type").equals("file"))continue;JSONArray objects=objects(e);for(int n=0;n<objects.length();n++){JSONObject o=objects.getJSONObject(n);if(seen.add(o.getString("hash")))bytes+=o.getLong("size");}if(e.has("chunks")&&assembled.add(e.getString("hash")))assembly+=e.getLong("size");}
        long expanded=0;for(int i=0;i<es.length();i++){JSONObject item=es.getJSONObject(i);if(item.getString("type").equals("file"))expanded+=item.getLong("size");}
        long download=bytes;
        if(m.getInt("format")==3){download=0;JSONObject packs=m.getJSONObject("packs");Iterator<String> keys=packs.keys();while(keys.hasNext())download+=packs.getJSONObject(keys.next()).getLong("size");}
        if(state.toFile().getUsableSpace()<bytes+assembly+expanded*2+(m.getInt("format")==3?download:0)+100*1024*1024)blockers.put("本地空间不足以暂存与回退");
        Path update=inventory.path("/data/adb/modules_update");if(Files.isDirectory(update))try(DirectoryStream<Path>d=Files.newDirectoryStream(update)){for(Path p:d)if(!p.getFileName().toString().equals(Util.ID))blockers.put("已有待重启模块更新，请先重启");}
        return Util.obj("id",m.getString("id"),"vault",m.getString("vault"),"roots",m.getJSONArray("roots").length(),"entries",es.length(),"downloadBytes",download,"blockers",blockers,"warnings",planWarnings,"requiresReboot",true);
    }
    Path stage(JSONObject m)throws Exception{
        validate(m);Path stage=state.resolve("restore-stage");
        if(Files.exists(stage)){Util.require(Files.exists(stage.resolve("manifest.json"))&&Util.hash(Util.bytes(Util.read(stage.resolve("manifest.json")))).equals(Util.hash(Util.bytes(m))),"暂存属于另一快照，请先清理或回退");Util.delete(stage.resolve("roots"));}
        Util.mkdir(stage);Util.write(stage.resolve("manifest.json"),m);Util.mkdir(stage.resolve("objects"));Util.mkdir(stage.resolve("roots"));progress("正在下载并校验恢复对象");
        JSONArray es=m.getJSONArray("entries"),rs=m.getJSONArray("roots");
        for(int i=0;i<rs.length();i++)Util.mkdir(stage.resolve("roots").resolve(rs.getJSONObject(i).getString("key")));
        Packs.download(this,m,stage);
        for(int i=0;i<es.length();i++){JSONObject e=es.getJSONObject(i);if(!e.has("chunks"))continue;Path complete=stage.resolve("objects").resolve(e.getString("hash"));if(Files.isRegularFile(complete,LinkOption.NOFOLLOW_LINKS)&&Files.size(complete)==e.getLong("size")&&Util.hash(complete).equals(e.getString("hash")))continue;
            Path temporary=complete.resolveSibling(complete.getFileName()+".assembling");
            try{try(OutputStream out=Files.newOutputStream(temporary)){JSONArray chunks=e.getJSONArray("chunks");for(int n=0;n<chunks.length();n++)Files.copy(stage.resolve("objects").resolve(chunks.getJSONObject(n).getString("hash")),out);}
                Util.require(Files.size(temporary)==e.getLong("size")&&Util.hash(temporary).equals(e.getString("hash")),"分块拼接后文件校验失败");Files.move(temporary,complete,StandardCopyOption.REPLACE_EXISTING);
            }finally{Files.deleteIfExists(temporary);}
        }
        for(String type:Arrays.asList("dir","file","whiteout","char","fifo","link"))for(int i=0;i<es.length();i++){
            JSONObject e=es.getJSONObject(i);if(!type.equals(e.getString("type")))continue;Path r=stage.resolve("roots").resolve(e.getString("root"));String relative=e.getString("path");Path p=relative.isEmpty()?r:Util.beneath(r,relative);
            if(type.equals("dir"))Util.mkdir(p);else{Util.mkdir(p.getParent());
                // Single-file roots are stored as a payload, not as a root directory replacement.
                if(relative.isEmpty())p=r.resolve("payload");
                if(type.equals("file"))Files.copy(stage.resolve("objects").resolve(e.getString("hash")),p,StandardCopyOption.REPLACE_EXISTING);
                else if(type.equals("link"))Files.createSymbolicLink(p,Paths.get(e.getString("target")));
                else Util.node(p,type,type.equals("char")?e.getLong("rdev"):0);
            }
        }
        Util.write(stage.resolve("manifest.json"),m);return stage;
    }
    static boolean safeDevice(long device){return Arrays.asList(259L,261L,263L,264L,265L,1280L,1281L,1282L).contains(device);}
    static void metadata(Path base,JSONObject e,Map<Integer,Integer> uids)throws Exception{
        String rel=e.getString("path"),t=e.getString("type");Path p;
        if(t.equals("link")&&!rel.isEmpty()){Path parent=Paths.get(rel).getParent();p=(parent==null?base:Util.beneath(base,parent.toString())).resolve(Paths.get(rel).getFileName());}
        else p=rel.isEmpty()?base:Util.beneath(base,rel);
        if(t.equals("link")){// lchown never follows a restored link into another tree.
            Os.lchown(p.toString(),uids.getOrDefault(e.getInt("uid"),e.getInt("uid")),uids.getOrDefault(e.getInt("gid"),e.getInt("gid")));return;
        }
        Os.chown(p.toString(),uids.getOrDefault(e.getInt("uid"),e.getInt("uid")),uids.getOrDefault(e.getInt("gid"),e.getInt("gid")));Os.chmod(p.toString(),e.getInt("mode"));
        for(String attr:Arrays.asList("trusted.overlay.opaque","security.capability","security.selinux"))if(e.has(attr))Os.setxattr(p.toString(),attr,Base64.getDecoder().decode(e.getString(attr)),0);
        p.toFile().setLastModified(e.getLong("mtime")*1000);
    }
}
