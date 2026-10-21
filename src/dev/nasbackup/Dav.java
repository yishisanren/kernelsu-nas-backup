package dev.nasbackup;
import org.json.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.*;
import okhttp3.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

final class Dav {
    final String base,auth;
    final OkHttpClient client=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).writeTimeout(60,TimeUnit.SECONDS).build();
    volatile long uploadedBytes;volatile int uploadedObjects;
    final Set<String> directories=new HashSet<>(),queuedHashes=new HashSet<>();
    final ArrayDeque<Future<?>> pending=new ArrayDeque<>();
    final Set<Path> uploadSpools=new HashSet<>();
    ExecutorService uploads;
    Dav(JSONObject c)throws Exception {
        URI u=new URI(c.getString("url"));
        Util.require("https".equals(u.getScheme())||("http".equals(u.getScheme())&&c.optBoolean("allowHttp")),"需要 HTTPS；HTTP 必须明确开启");
        Util.require(u.getHost()!=null&&u.getUserInfo()==null&&u.getQuery()==null&&u.getFragment()==null,"WebDAV 地址无效，账号请单独填写");
        base=c.getString("url").replaceAll("/+$","")+"/";
        Util.require(!c.optString("username").contains(":"),"用户名不能包含冒号");
        auth="Basic "+Base64.getEncoder().encodeToString((c.optString("username")+":"+c.optString("password")).getBytes(Util.UTF8));
    }
    static final class Reply {int code;byte[] body;Map<String,List<String>> headers;}
    Request.Builder builder(String path)throws Exception {
        Util.require(!path.startsWith("/")&&!path.contains("..")&&!path.contains("\\")&&!path.contains("%"),"远端路径无效");
        return new Request.Builder().url(base+path).header("Authorization",auth).header("Accept-Encoding","identity");
    }
    Reply request(String method,String path,byte[] body,Map<String,String> headers,int limit)throws Exception {
        Request.Builder b=builder(path);for(Map.Entry<String,String> h:headers.entrySet())b.header(h.getKey(),h.getValue());
        if(body==null&&Arrays.asList("PUT","PROPFIND").contains(method))body=new byte[0];
        b.method(method,body==null?null:RequestBody.create(MediaType.get("application/octet-stream"),body));
        try(Response response=client.newCall(b.build()).execute()) {
            Reply r=new Reply();r.code=response.code();r.headers=response.headers().toMultimap();
            Util.require(r.code<300||r.code>=400,"NAS 返回重定向；请填写最终地址");
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            if(!method.equals("HEAD")&&response.body()!=null)try(InputStream in=response.body().byteStream()){
                byte[] block=new byte[65536];int n;while((n=in.read(block))!=-1){Util.require(out.size()+n<=limit,"NAS 响应超出限制");out.write(block,0,n);}
            }r.body=out.toByteArray();return r;
        }
    }
    Reply req(String method,String path,byte[] body)throws Exception{return request(method,path,body,Collections.emptyMap(),16*1024*1024);}
    void ok(Reply r,int... codes)throws Exception{for(int c:codes)if(r.code==c)return;throw new IOException("WebDAV HTTP "+r.code);}
    synchronized void dirs(String path)throws Exception {String current="";for(String part:path.split("/")){if(part.isEmpty())continue;current+=part+"/";if(directories.contains(current))continue;ok(req("MKCOL",current,null),201,405);directories.add(current);}}
    List<String> list(String path)throws Exception {
        byte[] xml="<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>".getBytes(Util.UTF8);
        Reply r=request("PROPFIND",path,xml,Collections.singletonMap("Depth","1"),16*1024*1024);ok(r,207);
        String source=new String(r.body,Util.UTF8);Util.require(!source.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")&&!source.contains("<!ENTITY"),"拒绝含外部实体的 NAS 响应");
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setExpandEntityReferences(false);
        javax.xml.parsers.DocumentBuilder parser=f.newDocumentBuilder();parser.setEntityResolver((a,b)->{throw new org.xml.sax.SAXException("External entity denied");});
        NodeList hrefs=parser.parse(new org.xml.sax.InputSource(new StringReader(source))).getElementsByTagNameNS("DAV:","href");
        String prefix=new URI(base+path).getPath();if(!prefix.endsWith("/"))prefix+="/";Set<String> names=new TreeSet<>();
        for(int i=0;i<hrefs.getLength();i++){String p=new URI(hrefs.item(i).getTextContent()).getPath();if(!p.startsWith(prefix))continue;String n=p.substring(prefix.length()).replaceAll("/+$","");if(n.matches("[A-Za-z0-9_.-]+")&&!n.contains(".."))names.add(n);}
        return new ArrayList<>(names);
    }
    void put(String path,byte[] body)throws Exception{ok(req("PUT",path,body),200,201,204);}
    byte[] get(String path,int limit)throws Exception{Reply r=request("GET",path,null,Collections.emptyMap(),limit);ok(r,200);return r.body;}
    String objectPath(String hash)throws Exception{Util.require(hash.matches("[a-f0-9]{64}"),"无效内容校验值");return "objects/"+hash.substring(0,2)+"/"+hash;}
    boolean hasObject(String hash,long size)throws Exception {
        Reply h=req("HEAD",objectPath(hash),null);
        if(h.code==200){for(String k:h.headers.keySet())if(k.equalsIgnoreCase("Content-Length"))return Long.toString(size).equals(h.headers.get(k).get(0));return false;}
        ok(h,404);return false;
    }
    void object(Path file,String hash,long size)throws Exception {
        if(hasObject(hash,size))return;String p=objectPath(hash);dirs("objects/"+hash.substring(0,2));
        try(Response r=client.newCall(builder(p).put(RequestBody.create(MediaType.get("application/octet-stream"),file.toFile())).build()).execute()){
            Util.require(r.code()==200||r.code()==201||r.code()==204,"对象上传失败 HTTP "+r.code());
        }
        verifyRemote(p,hash,size,null);synchronized(this){uploadedBytes+=size;uploadedObjects++;}
    }
    // Sixteen small-file transfers use at most 128 MiB of queued spool data.
    // Large files run alone so a few large APKs cannot exhaust phone storage.
    void queueObject(Path file,String hash,long size)throws Exception {
        if(!queuedHashes.add(hash))return;
        if(size>8L*1024*1024){finishObjects();object(file,hash,size);return;}
        if(pending.size()>=16)await(pending.removeFirst());
        if(uploads==null)uploads=Executors.newFixedThreadPool(16);
        Path spool=file.resolveSibling("upload-"+UUID.randomUUID());
        Files.move(file,spool);uploadSpools.add(spool);
        pending.add(uploads.submit(()->{try{object(spool,hash,size);return null;}finally{Files.deleteIfExists(spool);}}));
    }
    static void await(Future<?> future)throws Exception {
        try{future.get();}catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IOException("上传任务失败",cause);}
    }
    void finishObjects()throws Exception {while(!pending.isEmpty())await(pending.removeFirst());}
    void closeUploads()throws Exception {
        if(uploads!=null){client.dispatcher().cancelAll();uploads.shutdownNow();Util.require(uploads.awaitTermination(70,TimeUnit.SECONDS),"上传任务尚未结束");}
        for(Path p:uploadSpools)Files.deleteIfExists(p);
    }
    void verifyRemote(String path,String hash,long size,Path output)throws Exception {
        java.security.MessageDigest md=java.security.MessageDigest.getInstance("SHA-256");long total=0;
        Request.Builder request=builder(path).get();
        // A full byte range prevents HTML-rewriting proxies from injecting scripts.
        // Servers may ignore Range and return 200; the complete hash remains mandatory.
        if(size>0)request.header("Range","bytes=0-"+(size-1));
        try(Response r=client.newCall(request.build()).execute()){
            Util.require((r.code()==200||r.code()==206)&&r.body()!=null,"对象下载失败 HTTP "+r.code());
            if(r.code()==206)Util.require(size>0&&("bytes 0-"+(size-1)+"/"+size).equals(r.header("Content-Range")),"对象响应字节范围不符");
            try(InputStream in=r.body().byteStream();OutputStream out=output==null?new OutputStream(){public void write(int b){}public void write(byte[] b,int o,int n){}}:Files.newOutputStream(output)){
                byte[] b=new byte[262144];int n;while((n=in.read(b))!=-1){total+=n;Util.require(total<=size,"对象大小超出清单 "+hash+" expected="+size+" received="+total);md.update(b,0,n);out.write(b,0,n);}
            }
        }
        Util.require(total==size&&Util.hex(md.digest()).equals(hash),"备份对象校验失败");
    }
}
