package dev.nasbackup;

import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Immutable, content-addressed ZIPs. The snapshot carries every reference it needs. */
final class Packs {
    static final long TARGET=16L*1024*1024,MAX=24L*1024*1024;
    final Engine engine;
    final JSONObject refs=new JSONObject(),packs=new JSONObject();
    final Set<String> used=new HashSet<>(),checked=new HashSet<>();
    JSONObject members=new JSONObject();
    ZipOutputStream zip;Path spool;long bytes;
    Packs(Engine e)throws Exception {
        engine=e;e.dav.dirs("packs");
        List<String> files=e.dav.list("vaults/"+e.vault()+"/snapshots/");Set<String> names=new HashSet<>(files);
        Collections.sort(files,Collections.reverseOrder());
        for(String f:files)if(f.matches("[0-9]+-[a-f0-9]{8}\\.sha256")&&names.contains(f.replace(".sha256",".json"))){
            JSONObject previous=e.manifest(e.vault(),f.substring(0,f.length()-7));
            if(previous.getInt("format")==3){copy(previous.getJSONObject("blobs"),refs);copy(previous.getJSONObject("packs"),packs);}break;
        }
    }
    static void copy(JSONObject from,JSONObject to)throws Exception {Iterator<String> keys=from.keys();while(keys.hasNext()){String key=keys.next();to.put(key,from.get(key));}}
    static String path(String hash)throws Exception {Util.require(hash.matches("[a-f0-9]{64}"),"无效数据包校验值");return "packs/"+hash+".zip";}
    void add(Path file,String hash,long size)throws Exception {
        used.add(hash);
        if(refs.has(hash)){
            JSONObject ref=refs.getJSONObject(hash);Util.require(ref.getLong("size")==size,"对象大小冲突");
            String pack=ref.getString("pack");
            if(checked.add(pack))Util.require(engine.dav.hasFile(path(pack),packs.getJSONObject(pack).getLong("size")),"已有数据包缺失或大小变化，请检查 NAS");
            return;
        }
        if(members.has(hash))return;
        Util.require(size<=Engine.CHUNK_SIZE,"数据包对象必须先分块");
        boolean bulk=size>=4L*1024*1024;
        // Isolate large blocks so changing one cannot force downloading its obsolete neighbour.
        if(zip!=null&&(bulk||bytes+size>TARGET||members.length()>=1024))finish();
        if(zip==null){spool=engine.state.resolve("spool/pack.zip");Util.mkdir(spool.getParent());zip=new ZipOutputStream(Files.newOutputStream(spool));zip.setLevel(Deflater.BEST_SPEED);}
        ZipEntry entry=new ZipEntry(hash);entry.setTime(0);
        if(bulk&&!compressible(file)){
            CRC32 crc=new CRC32();try(InputStream in=Files.newInputStream(file)){byte[] b=new byte[262144];int n;while((n=in.read(b))!=-1)crc.update(b,0,n);}
            entry.setMethod(ZipEntry.STORED);entry.setSize(size);entry.setCompressedSize(size);entry.setCrc(crc.getValue());
        }
        zip.putNextEntry(entry);Files.copy(file,zip);zip.closeEntry();members.put(hash,size);bytes+=size;
        if(bulk)finish();
    }
    static boolean compressible(Path file)throws Exception {
        byte[] sample=new byte[32768];int n;
        try(RandomAccessFile in=new RandomAccessFile(file.toFile(),"r")){in.seek(Math.max(0,in.length()/2-sample.length/2));n=in.read(sample);}
        Deflater deflater=new Deflater(Deflater.BEST_SPEED);
        try{deflater.setInput(sample,0,n);deflater.finish();return deflater.deflate(new byte[sample.length+1024])<n*0.95;}finally{deflater.end();}
    }
    void finish()throws Exception {
        if(zip==null)return;zip.close();zip=null;
        try{
            String hash=Util.hash(spool);long size=Files.size(spool);Util.require(size<=MAX,"数据包大小超限");
            engine.dav.file(spool,path(hash),hash,size);
            packs.put(hash,Util.obj("size",size,"members",members));checked.add(hash);
            Iterator<String> keys=members.keys();while(keys.hasNext()){String key=keys.next();refs.put(key,Util.obj("size",members.getLong(key),"pack",hash));}
        }finally{Files.deleteIfExists(spool);}
        members=new JSONObject();bytes=0;
    }
    void manifest(JSONObject m)throws Exception {
        finish();JSONObject selected=new JSONObject(),selectedPacks=new JSONObject();
        for(String hash:used){JSONObject ref=refs.getJSONObject(hash);selected.put(hash,ref);String pack=ref.getString("pack");selectedPacks.put(pack,packs.getJSONObject(pack));}
        m.put("blobs",selected).put("packs",selectedPacks);
    }
    void close()throws Exception {if(zip!=null){zip.close();zip=null;}if(spool!=null)Files.deleteIfExists(spool);}
    static void validate(JSONObject m,Map<String,Long> objects)throws Exception {
        JSONObject refs=m.getJSONObject("blobs"),packs=m.getJSONObject("packs");
        Util.require(refs.length()==objects.size()&&packs.length()<=objects.size(),"数据包索引数量不符");Set<String> used=new HashSet<>();
        for(Map.Entry<String,Long> object:objects.entrySet()){
            JSONObject ref=refs.getJSONObject(object.getKey());String pack=ref.getString("pack");path(pack);
            Util.require(ref.getLong("size")==object.getValue()&&packs.getJSONObject(pack).getJSONObject("members").getLong(object.getKey())==object.getValue(),"数据包对象引用不符");used.add(pack);
        }
        Util.require(used.size()==packs.length(),"清单含未引用数据包");Iterator<String> keys=packs.keys();
        while(keys.hasNext()){
            JSONObject pack=packs.getJSONObject(keys.next());long size=pack.getLong("size"),expanded=0;JSONObject members=pack.getJSONObject("members");
            Util.require(size>0&&size<=MAX&&members.length()>0&&members.length()<=1024,"数据包大小或条目数量无效");
            Iterator<String> hashes=members.keys();while(hashes.hasNext()){String hash=hashes.next();long n=members.getLong(hash);Util.require(hash.matches("[a-f0-9]{64}")&&n>=0&&n<=Engine.CHUNK_SIZE,"数据包条目无效");expanded+=n;}
            Util.require(expanded<=TARGET,"数据包解压大小超限");
        }
    }
    static boolean valid(Path p,String hash,long size)throws Exception {return Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&Files.size(p)==size&&Util.hash(p).equals(hash);}
    static void extract(Path zip,JSONObject pack,Path objects,Set<String> wanted)throws Exception {
        JSONObject members=pack.getJSONObject("members");Set<String> seen=new HashSet<>();
        try(ZipInputStream in=new ZipInputStream(Files.newInputStream(zip))){ZipEntry entry;
            while((entry=in.getNextEntry())!=null){String hash=entry.getName();Util.require(members.has(hash)&&seen.add(hash)&&!entry.isDirectory(),"数据包包含未知或重复条目");
                long size=members.getLong(hash),total=0;java.security.MessageDigest md=java.security.MessageDigest.getInstance("SHA-256");
                Path target=objects.resolve(hash),temp=objects.resolve(hash+".part");boolean write=wanted.contains(hash);
                try{
                    try(OutputStream out=write?Files.newOutputStream(temp):new OutputStream(){public void write(int b){}public void write(byte[] b,int o,int n){}}){
                        byte[] b=new byte[262144];int n;while((n=in.read(b))!=-1){total+=n;Util.require(total<=size,"数据包条目解压超限");md.update(b,0,n);out.write(b,0,n);}
                    }
                    Util.require(total==size&&Util.hex(md.digest()).equals(hash),"数据包条目校验失败");
                    if(write)Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);
                }finally{Files.deleteIfExists(temp);}
            }
        }
        Util.require(seen.size()==members.length(),"数据包缺少条目");
    }
    static void download(Engine e,JSONObject m,Path stage)throws Exception {
        Map<String,JSONObject> objects=new LinkedHashMap<>();JSONArray entries=m.getJSONArray("entries");
        for(int i=0;i<entries.length();i++){JSONObject entry=entries.getJSONObject(i);if(entry.getString("type").equals("file")){JSONArray refs=Engine.objects(entry);for(int n=0;n<refs.length();n++){JSONObject ref=refs.getJSONObject(n);objects.put(ref.getString("hash"),ref);}}}
        Map<String,Set<String>> groups=new LinkedHashMap<>();
        for(Map.Entry<String,JSONObject> item:objects.entrySet())if(!valid(stage.resolve("objects").resolve(item.getKey()),item.getKey(),item.getValue().getLong("size"))){
            String key=m.getInt("format")==3?m.getJSONObject("blobs").getJSONObject(item.getKey()).getString("pack"):item.getKey();
            groups.computeIfAbsent(key,k->new HashSet<>()).add(item.getKey());
        }
        Util.mkdir(stage.resolve("packs"));ExecutorService pool=Executors.newFixedThreadPool(4);ArrayDeque<Future<?>> pending=new ArrayDeque<>();
        try{
            for(Map.Entry<String,Set<String>> group:groups.entrySet()){
                if(pending.size()>=4)Dav.await(pending.removeFirst());
                pending.add(pool.submit(()->{
                    String hash=group.getKey();
                    if(m.getInt("format")==3){
                        JSONObject pack=m.getJSONObject("packs").getJSONObject(hash);Path file=stage.resolve("packs").resolve(hash+".zip"),temp=file.resolveSibling(hash+".part");
                        try{if(!valid(file,hash,pack.getLong("size"))){e.dav.verifyRemote(path(hash),hash,pack.getLong("size"),temp);Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}extract(file,pack,stage.resolve("objects"),group.getValue());}
                        finally{Files.deleteIfExists(temp);}
                    }else{
                        Path file=stage.resolve("objects").resolve(hash),temp=file.resolveSibling(hash+".part");
                        try{e.dav.verifyRemote(e.dav.objectPath(hash),hash,objects.get(hash).getLong("size"),temp);Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}
                    }return null;
                }));
            }
            while(!pending.isEmpty())Dav.await(pending.removeFirst());
        }finally{pool.shutdownNow();Util.require(pool.awaitTermination(70,TimeUnit.SECONDS),"恢复下载尚未结束");}
    }
}
