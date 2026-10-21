package dev.nasbackup;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.system.*;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

class Inventory {
    final Path root;
    final boolean sandbox;
    Inventory(Path root,boolean sandbox){this.root=root;this.sandbox=sandbox;}
    Path path(String p){return root.resolve(p.replaceFirst("^/",""));}
    JSONObject environment()throws Exception{
        return sandbox?Util.obj("device","fixture","api","37","abi","arm64-v8a","fingerprint","fixture","ksu","test","lsposed","test"):
            Util.obj("device",Util.prop("ro.product.device"),"api",Util.prop("ro.build.version.sdk"),"abi",Util.prop("ro.product.cpu.abi"),"fingerprint",Util.prop("ro.build.fingerprint"),"ksu",Util.run("/system/bin/su","-v").trim(),"lsposed",moduleVersion("zygisk_lsposed"));
    }
    String moduleVersion(String id){try{return props(path("/data/adb/modules/"+id+"/module.prop")).getProperty("version","");}catch(Exception e){return "";}}
    boolean enabled(Path module){Path live=path("/data/adb/modules/"+module.getFileName());return !Files.exists((Files.isDirectory(live)?live:module).resolve("disable"));}
    boolean removed(Path module){return Files.exists(module.resolve("remove"))||Files.exists(path("/data/adb/modules/"+module.getFileName()+"/remove"));}
    String metamodule()throws Exception {
        Path entry=path("/data/adb/metamodule");if(!Files.exists(entry,LinkOption.NOFOLLOW_LINKS))return "";
        Util.require(Files.isSymbolicLink(entry),"KernelSU metamodule 入口形式未适配");String target=Files.readSymbolicLink(entry).toString();String prefix=target.startsWith("/data/adb/modules/")?"/data/adb/modules/":target.startsWith("modules/")?"modules/":"";
        Util.require(!prefix.isEmpty(),"KernelSU metamodule 入口路径未适配");String id=target.substring(prefix.length());validId(id);return id;
    }
    static Properties props(Path p)throws Exception{Properties v=new Properties();try(Reader in=Files.newBufferedReader(p,Util.UTF8)){v.load(in);}return v;}
    static void validId(String s)throws Exception{Util.require(s.matches("[a-zA-Z][a-zA-Z0-9._-]{0,127}"),"模块标识无效");}
    static void validPackage(String s)throws Exception{Util.require(s.matches("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+"),"应用包名无效");}
    List<Path> modules()throws Exception{
        Path base=path("/data/adb/modules");Util.require(Files.isReadable(base)&&Files.isDirectory(base),"不能读取 KernelSU 模块目录");
        Map<String,Path> result=new TreeMap<>();for(Path dir:Arrays.asList(base,path("/data/adb/modules_update"))){if(!Files.isDirectory(dir))continue;
            try(DirectoryStream<Path>d=Files.newDirectoryStream(dir)){for(Path p:d)if(!p.getFileName().toString().equals(Util.ID)&&Files.isRegularFile(p.resolve("module.prop"))){validId(p.getFileName().toString());if(!dir.equals(base)&&!Files.exists(base.resolve(p.getFileName()).resolve("update")))continue;result.put(p.getFileName().toString(),p);}}
        }return new ArrayList<>(result.values());
    }
    JSONObject lsposed()throws Exception{
        Path p=path("/data/adb/lspd/config/modules_config.db");
        if(!Files.exists(p)){Util.require(moduleVersion("zygisk_lsposed").isEmpty(),"已安装 LSPosed，但配置数据库缺失");return Util.obj("present",false);}
        return Lsp.read(p);
    }
    JSONArray packages()throws Exception{
        JSONArray out=new JSONArray();if(sandbox)return out;
        Set<String> registered=Lsp.missingApks(lsposed(),Collections.emptySet());
        Matcher users=Pattern.compile("UserInfo\\{(\\d+):").matcher(Util.run("/system/bin/pm","list","users"));
        while(users.find()){
            int user=Integer.parseInt(users.group(1));String listing=Util.run("/system/bin/pm","list","packages","-f","-U","--user",Integer.toString(user));
            for(String line:listing.split("\n")){
                Matcher m=Pattern.compile("^package:(.+)=([^ ]+) uid:(\\d+).*$").matcher(line.trim());if(!m.matches())continue;
                Path base=Paths.get(m.group(1));boolean module;
                try(ZipFile apk=new ZipFile(base.toFile())){module=registered.contains(m.group(2))||apk.getEntry("assets/xposed_init")!=null||apk.getEntry("META-INF/xposed/java_init.list")!=null;}
                catch(IOException e){throw new IOException("无法检查已安装 APK: "+m.group(2));}
                if(!module)continue;String pkg=m.group(2);validPackage(pkg);
                JSONArray apks=new JSONArray();String paths=Util.run("/system/bin/pm","path","--user",Integer.toString(user),pkg);
                for(String a:paths.split("\n"))if(a.startsWith("package:"))apks.put(a.substring(8));
                Util.require(apks.length()>0,"模块 APK 不完整: "+pkg);
                out.put(Util.obj("package",pkg,"user",user,"uid",Integer.parseInt(m.group(3)),"apks",apks));
            }
        }return out;
    }
    static final Set<String> EXCLUDED=new HashSet<>(Arrays.asList("modules","modules_update","ksu","ksud","magisk","lspd",Util.ID,"post-fs-data.d","service.d","boot-completed.d","metamodule","plugins","private-network-backups"));
    Set<String> external(List<Path> modules,JSONObject config,JSONArray warnings)throws Exception{
        Set<String> paths=new TreeSet<>();Pattern p=Pattern.compile("/data/adb/([A-Za-z0-9_.-]+)(?:/[A-Za-z0-9_./-]*)?");
        for(Path module:modules){
            String id=module.getFileName().toString();String same="/data/adb/"+id;if(!EXCLUDED.contains(id)&&Files.exists(path(same)))paths.add(same);
            try(java.util.stream.Stream<Path> tree=Files.walk(module,3)){
                Iterator<Path> it=tree.iterator();while(it.hasNext()){
                    Path f=it.next();String n=f.getFileName().toString();if(!n.endsWith(".sh")||Files.isSymbolicLink(f)||Files.size(f)>1024*1024)continue;
                    Matcher m=p.matcher(new String(Files.readAllBytes(f),Util.UTF8));while(m.find()){String top=m.group(1);if(!EXCLUDED.contains(top)&&!top.toLowerCase(Locale.ROOT).contains("backup")&&!top.endsWith(".log")&&Files.exists(path("/data/adb/"+top)))paths.add("/data/adb/"+top);}
                }
            }
        }
        JSONArray extra=config.optJSONArray("extraPaths");if(extra!=null)for(int i=0;i<extra.length();i++){String s=extra.getString(i);validateExtra(s);Util.require(Files.exists(path(s)),"附加配置路径不存在: "+s);paths.add(s);}
        Set<String> collapsed=new TreeSet<>();for(String item:paths){boolean nested=false;for(String parent:paths)if(!item.equals(parent)&&item.startsWith(parent+"/"))nested=true;if(!nested)collapsed.add(item);}paths=collapsed;
        warnings.put("外部配置按模块脚本及附加路径识别；二进制内自定义路径需在附加配置中指定。加密配置可能需要重新设置。");return paths;
    }
    static void validateExtra(String s)throws Exception{
        Util.require(s.matches("/data/adb/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")&&!s.contains(".."),"附加配置仅允许 /data/adb 下的明确路径");
        for(String part:s.substring(10).split("/"))Util.require(!part.equals("."),"附加配置禁止点目录");
        String top=s.substring(10).split("/")[0];Util.require(!EXCLUDED.contains(top),"禁止备份或覆盖 Root 控制目录: "+top);
    }
    String quick()throws Exception{
        StringBuilder s=new StringBuilder();for(Path p:modules()){s.append(p).append(Util.hash(p.resolve("module.prop"))).append(enabled(p)).append(removed(p));}s.append(metamodule());
        if(!sandbox){Matcher users=Pattern.compile("UserInfo\\{(\\d+):").matcher(Util.run("/system/bin/pm","list","users"));while(users.find()){
            String list=Util.run("/system/bin/pm","list","packages","-f","-U","--user",users.group(1));s.append(list);for(String line:list.split("\n")){int end=line.lastIndexOf("=");if(line.startsWith("package:")&&end>8){Path apk=Paths.get(line.substring(8,end));if(Files.exists(apk))s.append(Files.size(apk)).append(Files.getLastModifiedTime(apk).toMillis());}}
        }}
        for(String suffix:Arrays.asList("","-wal")){Path wal=path("/data/adb/lspd/config/modules_config.db"+suffix);if(Files.exists(wal))s.append(Files.getLastModifiedTime(wal).toMillis()).append(Files.size(wal));}return Util.hash(s.toString().getBytes(Util.UTF8));
    }
    JSONObject inspect(JSONObject config)throws Exception{
        JSONArray warnings=new JSONArray();List<Path> mods=modules();JSONArray list=new JSONArray();for(Path p:mods)list.put(Util.obj("id",p.getFileName().toString(),"version",props(p.resolve("module.prop")).getProperty("version",""),"enabled",!Files.exists(p.resolve("disable"))));
        JSONObject ls=lsposed();return Util.obj("environment",environment(),"modules",list,"packages",packages(),"lsposed",ls,"externalPaths",new JSONArray(external(mods,config,warnings)),"warnings",warnings);
    }
    static JSONObject meta(Path p)throws Exception{
        StructStat st=Os.lstat(p.toString());String type=OsConstants.S_ISLNK(st.st_mode)?"link":OsConstants.S_ISDIR(st.st_mode)?"dir":OsConstants.S_ISREG(st.st_mode)?"file":OsConstants.S_ISCHR(st.st_mode)?(st.st_rdev==0?"whiteout":"char"):OsConstants.S_ISSOCK(st.st_mode)?"socket":OsConstants.S_ISFIFO(st.st_mode)?"fifo":"unsupported";
        JSONObject j=Util.obj("type",type,"mode",st.st_mode&07777,"uid",st.st_uid,"gid",st.st_gid,"size",st.st_size,"mtime",st.st_mtime);
        if(type.equals("char"))j.put("rdev",st.st_rdev);
        if(type.equals("link"))j.put("target",Os.readlink(p.toString()));
        if(!type.equals("link"))for(String attr:Arrays.asList("trusted.overlay.opaque","security.capability","security.selinux")){try{byte[] a=Os.getxattr(p.toString(),attr);if(a!=null)j.put(attr,Base64.getEncoder().encodeToString(a));}catch(ErrnoException e){if(e.errno!=OsConstants.ENODATA&&e.errno!=OsConstants.ENOTSUP)throw e;}}
        return j;
    }
}
