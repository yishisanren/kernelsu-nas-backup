package dev.nasbackup;

import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import android.system.Os;

/** The same fixture runs against the released beta.4 Engine and the new Engine. */
public final class TransferBenchmark {
    static void seed(Path root)throws Exception {
        Path module=root.resolve("data/adb/modules/bench");Util.mkdir(module);
        Files.write(module.resolve("module.prop"),"id=bench\nname=Benchmark\nversion=1\nversionCode=1\n".getBytes(Util.UTF8));
        byte[] block=new byte[4096];Arrays.fill(block,(byte)'x');
        for(int i=0;i<640;i++){byte[] prefix=("configuration-"+i+"\n").getBytes(Util.UTF8);System.arraycopy(prefix,0,block,0,prefix.length);Files.write(module.resolve("config-"+i),block);}
        Random random=new Random(20261024);byte[] binary=new byte[65536];
        for(int i=0;i<16;i++){random.nextBytes(binary);Files.write(module.resolve("binary-"+i),binary);}
        try(OutputStream out=Files.newOutputStream(module.resolve("large.bin"))){for(int i=0;i<640;i++){random.nextBytes(binary);out.write(binary);}}
        Files.createSymbolicLink(module.resolve("link"),Paths.get("config-0"));Util.node(module.resolve("fifo"),"fifo",0);Os.chmod(module.resolve("config-0").toString(),0600);
    }
    static JSONObject config(String url,String vault)throws Exception{return Util.obj("url",url,"allowHttp",true,"username","test","password","test","vault",vault,"extraPaths",new JSONArray(),"zipDirs",new JSONArray());}
    static JSONObject stats(Dav dav)throws Exception{return new JSONObject(new String(dav.get("stats",16384),Util.UTF8));}
    static JSONObject delta(JSONObject before,JSONObject after,long start)throws Exception {
        JSONObject result=Util.obj("millis",(System.nanoTime()-start)/1000000);Iterator<String> keys=after.keys();while(keys.hasNext()){String key=keys.next();result.put(key,after.getLong(key)-before.optLong(key));}return result;
    }
    static int verify(JSONObject m,Path root)throws Exception {
        int checks=0;Map<String,JSONObject> roots=new HashMap<>();JSONArray rs=m.getJSONArray("roots"),entries=m.getJSONArray("entries");
        for(int i=0;i<rs.length();i++)roots.put(rs.getJSONObject(i).getString("key"),rs.getJSONObject(i));
        for(int i=0;i<entries.length();i++){
            JSONObject entry=entries.getJSONObject(i);Path file=root.resolve(roots.get(entry.getString("root")).getString("destination").substring(1)).resolve(entry.getString("path"));JSONObject actual=Inventory.meta(file);
            Util.require(actual.getString("type").equals(entry.getString("type")),"恢复类型不符");checks++;
            if(entry.getString("type").equals("file")){Util.require(Util.hash(file).equals(entry.getString("hash")),"恢复内容不符");checks++;}
            for(String key:Arrays.asList("uid","gid","mode")){Util.require(actual.getInt(key)==entry.getInt(key),"恢复元数据不符 "+key);checks++;}
            if(entry.getString("type").equals("link")){Util.require(actual.getString("target").equals(entry.getString("target")),"恢复链接不符");checks++;}
        }return checks;
    }
    public static void main(String[] args){try{
        Util.require(Util.prop("ro.kernel.qemu").equals("1")&&Os.getuid()==0,"只允许一次性 root 模拟器");
        Path base=Paths.get("/data/local/tmp/nasbackup-transfer-benchmark");Util.delete(base);Util.mkdir(base);Path source=base.resolve("source");seed(source);
        JSONObject config=config(args[0],args[1]),report=Util.obj("version",Util.VERSION,"physicalDeviceModified",false,"extraPrefetchUsed",false);Inventory inventory=new Inventory(source,true);
        Engine first=new Engine(base.resolve("backup"),inventory,config);JSONObject before=stats(first.dav);long start=System.nanoTime();first.backup();report.put("baselineBackup",delta(before,stats(first.dav),start));
        Engine unchanged=new Engine(base.resolve("fresh-client"),inventory,config);before=stats(unchanged.dav);start=System.nanoTime();unchanged.backup();report.put("unchangedBackup",delta(before,stats(unchanged.dav),start));Util.require(unchanged.dav.uploadedBytes==0,"无变化备份上传了内容");
        Files.write(source.resolve("data/adb/modules/bench/config-0"),"changed configuration\n".getBytes(Util.UTF8));
        try(RandomAccessFile file=new RandomAccessFile(source.resolve("data/adb/modules/bench/large.bin").toFile(),"rw")){file.seek(Engine.CHUNK_SIZE+123);file.write(7);}
        Engine changed=new Engine(base.resolve("changed"),inventory,config);before=stats(changed.dav);start=System.nanoTime();JSONObject result=changed.backup();report.put("incrementalBackup",delta(before,stats(changed.dav),start));
        JSONObject manifest=changed.manifest(args[1],result.getString("id"));report.put("format",manifest.getInt("format")).put("entries",manifest.getJSONArray("entries").length());
        Path target=base.resolve("target");Engine restoring=new Engine(base.resolve("restore"),new Inventory(target,true),config);Restore restore=new Restore(restoring);
        before=stats(restoring.dav);start=System.nanoTime();restore.prepare(manifest);report.put("formalRestorePrepare",delta(before,stats(restoring.dav),start));
        restore.early();restore.finishApps();restore.early();Util.require(restore.status().getString("phase").equals("complete"),"恢复未完成");report.put("restoredAssertions",verify(manifest,target));
        System.out.println(report);Util.delete(base);
    }catch(Throwable e){e.printStackTrace();System.exit(1);}}
}
