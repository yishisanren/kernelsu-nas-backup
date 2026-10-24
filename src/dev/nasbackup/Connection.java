package dev.nasbackup;
import org.json.*;
import java.nio.file.*;
import java.net.*;
import java.io.*;
import java.util.*;

/** Select once per operation; pin the repository independently of its network origin. */
final class Connection {
    static final String MARKER=".nasbackup-repository.json";
    static boolean unavailable(Exception ex){
        if(ex instanceof javax.net.ssl.SSLException)return false;
        return ex instanceof ConnectException||ex instanceof UnknownHostException||ex instanceof NoRouteToHostException||ex instanceof SocketException||ex instanceof java.net.SocketTimeoutException||(ex instanceof InterruptedIOException&&"timeout".equals(ex.getMessage()));
    }
    static Dav open(Path state,JSONObject original)throws Exception {JSONObject c=Settings.normalize(original);return open(state,c,c.optString("internalOrigin").isEmpty()?"":Wifi.current());}
    static Dav open(Path state,JSONObject source,String ssid)throws Exception {
        JSONObject c=Settings.normalize(source);Util.require(Settings.configured(c),"请先保存 WebDAV 地址和共用路径");
        boolean matches=false;JSONArray names=c.getJSONArray("wifiNames");for(int i=0;i<names.length();i++)if(!ssid.isEmpty()&&ssid.equals(names.getString(i)))matches=true;
        String internal=c.getString("internalOrigin"),external=c.getString("externalOrigin"),mode="external",reason=ssid.isEmpty()?"未识别到 Wi-Fi，使用外网或默认地址":"Wi-Fi 未匹配，使用外网地址";Dav chosen=null;
        if(matches&&!internal.isEmpty()){
            try{chosen=new Dav(Settings.at(c,internal));probe(chosen);mode="internal";reason="Wi-Fi 名称匹配，内网可达";}
            catch(Exception ex){if(!unavailable(ex))throw ex;reason="内网连接不可达，已回退外网";chosen=null;}
        }
        if(chosen==null){chosen=new Dav(Settings.at(c,external));probe(chosen);}
        String selected=mode.equals("internal")?internal:external;Path pin=state.resolve("repositories/"+Util.hash(c.getString("path").getBytes(Util.UTF8))+".json");
        JSONObject saved=Files.exists(pin)?Util.read(pin):new JSONObject();String expected=saved.optString("id"),id=marker(chosen);
        if(id.isEmpty()){
            Util.require(expected.isEmpty(),"当前地址没有已绑定的备份库标识，请检查地址和共用路径");
            byte[] value=Util.bytes(Util.obj("format",1,"id",UUID.randomUUID().toString()));Dav.Reply r=chosen.request("PUT",MARKER,value,Collections.singletonMap("If-None-Match","*"),4096);chosen.ok(r,200,201,204,412);id=marker(chosen);Util.require(!id.isEmpty(),"无法建立或回读备份库标识");
        }
        Util.require(expected.isEmpty()||expected.equals(id),"地址对应不同的备份库，已停止操作");
        Set<String> verified=new LinkedHashSet<>();JSONArray prior=saved.optJSONArray("origins");if(prior!=null)for(int i=0;i<prior.length();i++)verified.add(prior.getString(i));verified.add(selected);boolean deferred=false;
        for(String other:Arrays.asList(external,internal))if(!other.isEmpty()&&!verified.contains(other)){
            // Never send NAS credentials to a private address on an unmatched Wi-Fi.
            if(other.equals(internal)){deferred=true;continue;}
            try{Dav alternate=new Dav(Settings.at(c,other));probe(alternate);Util.require(id.equals(marker(alternate)),"内外网没有指向同一个备份库，请检查 NAS 映射和共用路径");verified.add(other);}
            catch(Exception ex){if(!unavailable(ex))throw ex;deferred=true;}
        }
        chosen.repositoryId=id;chosen.checkRepository();Util.write(pin,Util.obj("id",id,"origins",new JSONArray(verified)));
        Util.write(state.resolve("connection.json"),Util.obj("mode",mode,"reason",reason,"ssid",ssid,"repositoryVerified",!deferred,"peerPending",deferred,"time",System.currentTimeMillis()));return chosen;
    }
    static void probe(Dav d)throws Exception {
        byte[] xml="<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>".getBytes(Util.UTF8);d.ok(d.probe("PROPFIND","",xml,Collections.singletonMap("Depth","0"),65536),207);
    }
    static String marker(Dav d)throws Exception {
        Dav.Reply r=d.probe("GET",MARKER,null,Collections.emptyMap(),4096);if(r.code==404)return "";d.ok(r,200);JSONObject j=new JSONObject(new String(r.body,Util.UTF8));String id=j.getString("id");Util.require(j.getInt("format")==1&&id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"),"备份库标识无效");return id;
    }
}
