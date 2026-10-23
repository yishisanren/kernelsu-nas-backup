package dev.nasbackup;

import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import android.system.Os;

public final class PackedTest {
    static int passed;static JSONArray checks=new JSONArray();
    static void check(boolean ok,String name)throws Exception{Util.require(ok,name);passed++;checks.put(name);System.out.println("PASS "+name);}
    interface Action{void run()throws Exception;}
    static void reject(Action action,String name)throws Exception{boolean failed=false;try{action.run();}catch(Exception e){failed=true;}check(failed,name);}
    public static void main(String[] args){try{
        Util.require(Util.prop("ro.kernel.qemu").equals("1")&&Os.getuid()==0,"只允许一次性模拟器");
        Path base=Paths.get("/data/local/tmp/nasbackup-packed-test");Util.delete(base);Util.mkdir(base);Path source=base.resolve("source");TransferBenchmark.seed(source);Inventory inventory=new Inventory(source,true);
        Files.write(source.resolve("data/adb/modules/bench/empty"),new byte[0]);check(!Packs.compressible(source.resolve("data/adb/modules/bench/large.bin")),"随机大块使用ZIP存储模式避免无效压缩");
        JSONObject config=TransferBenchmark.config(args[0],"packed-regression");Path state=base.resolve("state");Engine first=new Engine(state,inventory,config);JSONObject one=first.backup(),manifest=first.manifest(first.vault(),one.getString("id"));
        check(manifest.getInt("format")==3&&first.dav.uploadedObjects>=3,"默认压缩分包且大文件跨包");
        JSONObject packs=manifest.getJSONObject("packs");Iterator<String> keys=packs.keys();long raw=0,compressed=0;
        while(keys.hasNext()){String hash=keys.next();JSONObject pack=packs.getJSONObject(hash);long size=pack.getLong("size");check(size<=Packs.MAX,"分包未超过网关限制");first.dav.verifyRemote(Packs.path(hash),hash,size,null);compressed+=size;Iterator<String> members=pack.getJSONObject("members").keys();while(members.hasNext())raw+=pack.getJSONObject("members").getLong(members.next());}
        check(compressed<raw,"混合文本二进制负载实际压缩");
        Engine cleanClient=new Engine(base.resolve("new-client"),inventory,config);cleanClient.backup();check(cleanClient.dav.uploadedObjects==0,"本机索引丢失后从NAS已提交快照复用全部分包");
        Files.write(source.resolve("data/adb/modules/bench/config-0"),"new content".getBytes(Util.UTF8));Files.delete(source.resolve("data/adb/modules/bench/config-1"));
        Engine changed=new Engine(state,inventory,config);JSONObject two=changed.backup(),latest=changed.manifest(changed.vault(),two.getString("id"));
        check(changed.dav.uploadedObjects==1&&changed.dav.uploadedBytes<4096,"仅单个变化配置入增量包，删除只改索引");
        Files.write(source.resolve("data/adb/modules/bench/interrupted"),"interrupted content".getBytes(Util.UTF8));first.dav.put("control/fail-next-object",new byte[0]);int snapshots=first.dav.list("vaults/packed-regression/snapshots/").size();
        reject(()->new Engine(state,inventory,config).backup(),"分包上传半写失败");check(first.dav.list("vaults/packed-regression/snapshots/").size()==snapshots,"失败不发布完整快照标记");
        try(java.util.stream.Stream<Path> files=Files.list(state.resolve("spool"))){check(files.count()==0,"失败清理压缩与源文件暂存");}
        Engine retried=new Engine(state,inventory,config);retried.backup();check(retried.dav.uploadedObjects==1,"重试修复半包并复用已提交分包");
        JSONObject invalid=new JSONObject(latest.toString());invalid.getJSONObject("blobs").getJSONObject(invalid.getJSONObject("blobs").keys().next()).put("pack","../escape");reject(()->Engine.validate(invalid),"拒绝索引路径穿越");
        String pack=latest.getJSONObject("packs").keys().next();long size=latest.getJSONObject("packs").getJSONObject(pack).getLong("size");byte[] original=first.dav.get(Packs.path(pack),(int)Packs.MAX);byte[] corrupt=original.clone();corrupt[corrupt.length/2]^=1;first.dav.put(Packs.path(pack),corrupt);
        Path restoreState=base.resolve("restore"),target=base.resolve("target");Engine restoring=new Engine(restoreState,new Inventory(target,true),config);Restore restore=new Restore(restoring);
        reject(()->restore.prepare(latest),"同大小损坏包阻止正式恢复准备");check(!Files.exists(target)&&restore.status().getString("phase").equals("none"),"校验失败未修改任何恢复目标");
        first.dav.put(Packs.path(pack),original);restore.prepare(latest);restore.early();restore.finishApps();restore.early();check(TransferBenchmark.verify(latest,target)>3000,"正式恢复全量内容权限链接FIFO一致");check(!Files.exists(target.resolve("data/adb/modules/bench/config-1")),"删除文件在所选完整快照中不复活");
        check(Files.isRegularFile(restoreState.resolve("restore-stage/packs/"+pack+".zip")),"恢复保留已校验分包以供中断继续");
        JSONObject exported=new Engine(base.resolve("exporter"),inventory,config).export(latest);Path archive=Paths.get(exported.getString("path"));
        check(Util.hash(archive).equals(exported.getString("sha256")),"单ZIP导出校验值正确");
        Path standalone=base.resolve("standalone");try(ZipInputStream zip=new ZipInputStream(Files.newInputStream(archive))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){Path file=Util.beneath(standalone,entry.getName());Util.mkdir(file.getParent());Files.copy(zip,file);}}
        String prefix="vaults/packed-regression/snapshots/"+latest.getString("id");check(Files.isRegularFile(standalone.resolve(prefix+".json"))&&Files.isRegularFile(standalone.resolve(prefix+".sha256")),"单ZIP包含完整快照和提交标记");
        Iterator<String> packKeys=latest.getJSONObject("packs").keys();while(packKeys.hasNext()){String hash=packKeys.next();check(Packs.valid(standalone.resolve(Packs.path(hash)),hash,latest.getJSONObject("packs").getJSONObject(hash).getLong("size")),"单ZIP内所有引用分包独立完整");}
        first.dav.dirs("export-only");JSONObject exportConfig=new JSONObject(config.toString()).put("url",args[0]+"export-only/");Dav exportDav=new Dav(exportConfig);
        try(java.util.stream.Stream<Path> files=Files.walk(standalone)){Iterator<Path> paths=files.iterator();while(paths.hasNext()){Path file=paths.next();if(!Files.isRegularFile(file))continue;String relative=standalone.relativize(file).toString();exportDav.dirs(relative.substring(0,relative.lastIndexOf('/')));exportDav.put(relative,Files.readAllBytes(file));}}
        Path exportedTarget=base.resolve("exported-target");Engine exportedEngine=new Engine(base.resolve("exported-restore"),new Inventory(exportedTarget,true),exportConfig);JSONObject independent=exportedEngine.manifest("packed-regression",latest.getString("id"));Restore independentRestore=new Restore(exportedEngine);independentRestore.prepare(independent);independentRestore.early();independentRestore.finishApps();independentRestore.early();
        check(TransferBenchmark.verify(independent,exportedTarget)>3000,"只凭单ZIP解出的独立DAV目录完成正式恢复");
        // Authenticated pack hashes do not replace the per-member whitelist and expansion bounds.
        Path hostile=base.resolve("hostile.zip");try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(hostile))){zip.putNextEntry(new ZipEntry("../escape"));zip.write(1);zip.closeEntry();}
        reject(()->Packs.extract(hostile,Util.obj("members",new JSONObject()),base.resolve("hostile-output"),Collections.emptySet()),"拒绝压缩包内部路径穿越");
        String hash=Util.hash(new byte[]{1});Path oversized=base.resolve("oversized.zip");try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(oversized))){zip.putNextEntry(new ZipEntry(hash));zip.write(1);zip.closeEntry();}
        reject(()->Packs.extract(oversized,Util.obj("members",Util.obj(hash,0)),base.resolve("oversized-output"),Collections.emptySet()),"解压超过索引声明大小立即拒绝");
        JSONObject report=Util.obj("passed",passed,"checks",checks,"physicalDeviceModified",false);System.out.println(report);Util.delete(base);
    }catch(Throwable e){e.printStackTrace();System.exit(1);}}
}
