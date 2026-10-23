package dev.nasbackup;
import org.json.*;
import java.nio.file.*;
import java.nio.channels.*;
import java.io.*;
import java.util.*;

public final class Main {
    static final Path STATE=Paths.get("/data/adb/nas_module_backup");
    static Inventory inventory(){return new Inventory(Paths.get("/"),false);}
    static JSONObject config()throws Exception{return Files.exists(STATE.resolve("config.json"))?Util.read(STATE.resolve("config.json")):Util.obj("auto",true,"zipDirs",new JSONArray().put("/storage/emulated/0/Download"));}
    static Engine engine()throws Exception{return new Engine(STATE,inventory(),config());}
    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((t,x)->{System.err.println(Util.safeError(x));System.exit(1);});
        try {
            Util.require(android.system.Os.getuid()==0,"需要 KernelSU Root 权限");Util.mkdir(STATE);
            String command=args.length==0?"status":args[0];
            if(command.equals("daemon")){daemon();return;}
            JSONObject request=new JSONObject();if(args.length>1&&args[1].equals("stdin")){
                ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] block=new byte[4096];int n;while((n=System.in.read(block))!=-1){Util.require(b.size()+n<128*1024,"请求过大");b.write(block,0,n);}request=new JSONObject(new String(b.toByteArray(),Util.UTF8));
            }
            if(command.equals("status")){System.out.println(status());return;}
            try(FileChannel channel=FileChannel.open(STATE.resolve("operation.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=channel.tryLock()){
                Util.require(lock!=null,"已有备份或恢复任务运行中");JSONObject result=dispatch(command,request);System.out.println(Util.obj("ok",true,"result",result));
            }
        }catch(Throwable x){
            try{JSONObject error=Util.obj("ok",false,"phase","操作失败","error",Util.safeError(x),"time",System.currentTimeMillis());Util.write(STATE.resolve("status.json"),error);System.out.println(error);}catch(Exception ignored){}
            System.exit(1);
        }
    }
    static JSONObject status()throws Exception {
        JSONObject c=config();boolean password=!c.optString("password").isEmpty();c.remove("password");c.put("passwordSaved",password);
        JSONObject restore=Files.exists(STATE.resolve("restore.json"))?Util.read(STATE.resolve("restore.json")):Util.obj("phase","none");
        // Journal contains local paths and APK identities; expose only progress in the UI.
        return Util.obj("ok",true,"config",c,"status",Files.exists(STATE.resolve("status.json"))?Util.read(STATE.resolve("status.json")):Util.obj("phase","尚未备份"),"restore",Util.obj("phase",restore.optString("phase"),"error",restore.optString("error")),"lastBackup",Files.exists(STATE.resolve("last-backup.json"))?Util.read(STATE.resolve("last-backup.json")):new JSONObject());
    }
    static JSONObject dispatch(String command,JSONObject request)throws Exception {
        if(command.equals("configure")){
            JSONObject c=request;JSONObject old=config();if(!c.has("password")||c.optString("password").isEmpty())c.put("password",old.optString("password"));new Dav(c);
            for(String field:Arrays.asList("zipDirs","extraPaths")){JSONArray a=c.optJSONArray(field);if(a==null)c.put(field,new JSONArray());else for(int i=0;i<a.length();i++)Util.require(a.getString(i).length()<1024,"配置路径过长");}
            JSONArray extra=c.getJSONArray("extraPaths");for(int i=0;i<extra.length();i++)Inventory.validateExtra(extra.getString(i));
            Util.write(STATE.resolve("config.json"),c);return Util.obj("saved",true);
        }
        if(command.equals("inspect")){JSONObject info=inventory().inspect(config());JSONObject ls=info.getJSONObject("lsposed");JSONObject counts=new JSONObject();if(ls.optBoolean("present")){JSONObject tables=ls.getJSONObject("tables");Iterator<String> keys=tables.keys();while(keys.hasNext()){String key=keys.next();counts.put(key,tables.getJSONArray(key).length());}}info.put("lsposed",Util.obj("present",ls.optBoolean("present"),"version",ls.optInt("version"),"counts",counts));return info;}
        Engine e=command.equals("early")?new Engine(STATE,inventory(),new JSONObject(),false):engine();Restore restore=new Restore(e);
        switch(command){
            case "test":e.dav.dirs("vaults");e.dav.dirs("objects");return Util.obj("connected",true,"vaults",new JSONArray(e.dav.list("vaults/")));
            case "backup":Util.require(Arrays.asList("none","complete","rolled-back").contains(restore.status().getString("phase")),"恢复过程中暂停备份");return e.backup();
            case "history":{
                JSONArray result=new JSONArray();for(String vault:e.dav.list("vaults/")){if(!vault.matches("[A-Za-z0-9_-]{1,80}"))continue;
                    List<String> files=e.dav.list("vaults/"+vault+"/snapshots/");Set<String> names=new HashSet<>(files);
                    for(String f:files)if(f.matches("[0-9]+-[a-f0-9]{8}\\.json")&&names.contains(f.replace(".json",".sha256")))result.put(Util.obj("vault",vault,"id",f.substring(0,f.length()-5)));
                }return Util.obj("snapshots",result);
            }
            case "plan":{JSONObject m=e.manifest(request.getString("vault"),request.getString("id"));JSONObject plan=e.plan(m);Util.write(STATE.resolve("plan.json"),Util.obj("manifest",m,"digest",Util.hash(Util.bytes(m)),"created",System.currentTimeMillis()));return plan.put("digest",Util.hash(Util.bytes(m)));}
            case "restore":{JSONObject p=Util.read(STATE.resolve("plan.json"));Util.require(System.currentTimeMillis()-p.getLong("created")<30*60*1000,"恢复预检已过期，请重新检查");Util.require(request.getString("digest").equals(p.getString("digest")),"恢复确认与预检不匹配");return restore.prepare(p.getJSONObject("manifest"));}
            case "export":return e.export(e.manifest(request.getString("vault"),request.getString("id")));
            case "rollback":restore.requestRollback();return Util.obj("phase","rollback-requested");
            case "clean":restore.clean();return Util.obj("cleaned",true);
            case "early":restore.early();return Util.obj("phase",restore.status().getString("phase"));
            default:throw new IOException("未知命令");
        }
    }
    static void daemon()throws Exception {
        try(FileChannel channel=FileChannel.open(STATE.resolve("daemon.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock daemon=channel.tryLock()){
            if(daemon==null)return;String candidate="";long changedAt=0,nextAttempt=0;
            while(true){
                try {
                    JSONObject c=config();if(c.has("url")&&Util.prop("sys.boot_completed").equals("1")){
                        try(FileChannel op=FileChannel.open(STATE.resolve("operation.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=op.tryLock()){
                            if(lock!=null){Engine e=new Engine(STATE,inventory(),c);Restore r=new Restore(e);r.finishApps();r.rollbackApps();String phase=r.status().getString("phase");
                                if(c.optBoolean("auto",true)&&Arrays.asList("none","complete","rolled-back").contains(phase)&&System.currentTimeMillis()>=nextAttempt){
                                    String sig=e.inventory.quick();long now=System.currentTimeMillis();JSONObject schedule=Files.exists(STATE.resolve("schedule.json"))?Util.read(STATE.resolve("schedule.json")):new JSONObject();
                                    if(!sig.equals(candidate)){candidate=sig;changedAt=now;}
                                    if(Schedule.due(now,schedule.optLong("success"),schedule.optString("signature"),sig,changedAt,nextAttempt)){
                                        nextAttempt=now+300000;e.backup();Util.write(STATE.resolve("schedule.json"),Util.obj("signature",sig,"success",System.currentTimeMillis()));
                                    }
                                }
                            }
                        }
                    }
                }catch(Exception x){nextAttempt=System.currentTimeMillis()+300000;Util.write(STATE.resolve("status.json"),Util.obj("phase","等待重试","error",Util.safeError(x),"retryAfter",nextAttempt));}
                Thread.sleep(60000);
            }
        }
    }
}
