package dev.nasbackup;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class Util {
    static final String ID = "nas_module_backup";
    static final String VERSION = "0.1.0-beta.4";
    static final java.nio.charset.Charset UTF8 = StandardCharsets.UTF_8;
    static String hash(byte[] data) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(data)); }
    static String hash(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) { byte[] b = new byte[262144]; int n; while ((n=in.read(b))!=-1) md.update(b,0,n); }
        return hex(md.digest());
    }
    static String hex(byte[] b) { StringBuilder s=new StringBuilder(); for (byte v:b) s.append(String.format(Locale.ROOT,"%02x",v&255)); return s.toString(); }
    static JSONObject obj(Object... pairs) throws Exception { JSONObject o=new JSONObject(); for(int i=0;i<pairs.length;i+=2)o.put((String)pairs[i],pairs[i+1]); return o; }
    static byte[] bytes(JSONObject o) { return o.toString().getBytes(UTF8); }
    static JSONObject read(Path p) throws Exception { return new JSONObject(new String(Files.readAllBytes(p),UTF8)); }
    static void mkdir(Path p) throws Exception { if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)){require(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS),"目录路径不是普通目录");return;}Files.createDirectories(p);android.system.Os.chmod(p.toString(),0700); }
    static void write(Path p, byte[] b) throws Exception {
        mkdir(p.getParent()); Path tmp=p.resolveSibling(p.getFileName()+".tmp-"+UUID.randomUUID());
        try { Files.write(tmp,b,StandardOpenOption.CREATE_NEW); android.system.Os.chmod(tmp.toString(),0600);
            try(FileOutputStream f=new FileOutputStream(tmp.toFile(),true)){f.getFD().sync();}
            Files.move(tmp,p,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }
    static void write(Path p, JSONObject j) throws Exception { write(p,bytes(j)); }
    static Path beneath(Path root,String relative) throws Exception {
        if(relative.isEmpty() || relative.indexOf('\0')>=0 || relative.indexOf('\\')>=0 || relative.startsWith("/"))throw new IOException("无效相对路径");
        for(String segment:relative.split("/",-1))require(!segment.isEmpty()&&!segment.equals(".")&&!segment.equals(".."),"相对路径包含无效片段");
        Path p=root.resolve(relative).normalize(); if(!p.startsWith(root.normalize()) || p.equals(root.normalize()))throw new IOException("路径越界");
        Path c=root; for(Path part:root.relativize(p)){c=c.resolve(part);if(Files.isSymbolicLink(c))throw new IOException("拒绝经过符号链接写入: "+relative);}
        return p;
    }
    static String run(String... args) throws Exception {
        Process p=new ProcessBuilder(args).redirectErrorStream(true).start();
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        Thread reader=new Thread(()->{try{byte[] b=new byte[8192];int n;while((n=p.getInputStream().read(b))!=-1){if(out.size()<8*1024*1024)out.write(b,0,n);}}catch(IOException ignored){}});
        reader.start(); if(!p.waitFor(120,TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("系统命令超时: "+args[0]);}reader.join();
        String s=new String(out.toByteArray(),UTF8); if(p.exitValue()!=0)throw new IOException("系统命令失败: "+args[0]+" ("+p.exitValue()+")"); return s;
    }
    static String prop(String name) { try{return run("/system/bin/getprop",name).trim();}catch(Exception e){return "";} }
    static boolean unlocked(int user)throws Exception{return run("/system/bin/am","get-started-user-state",Integer.toString(user)).trim().equals("RUNNING_UNLOCKED");}
    static void require(boolean ok,String message) throws IOException { if(!ok)throw new IOException(message); }
    static void node(Path path,String type,long device)throws Exception {
        if(type.equals("fifo"))run("/system/bin/mkfifo",path.toString());
        else{require(device==0||Engine.safeDevice(device),"禁止创建敏感设备节点");run("/system/bin/mknod",path.toString(),"c",Long.toString(device>>8),Long.toString(device&255));}
    }
    static void delete(Path p) throws Exception {
        if(!Files.exists(p,LinkOption.NOFOLLOW_LINKS))return;
        if(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)){try(DirectoryStream<Path>d=Files.newDirectoryStream(p)){for(Path c:d)delete(c);}}
        Files.delete(p);
    }
    static String safeError(Throwable e) { return e instanceof java.net.ConnectException ? "无法连接 NAS" : e instanceof javax.net.ssl.SSLException ? "TLS 证书校验失败" : e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage()).replaceAll("https?://[^\\s]+","[URL]"); }
}
