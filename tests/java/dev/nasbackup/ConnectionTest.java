package dev.nasbackup;
import org.json.*;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import android.system.Os;

/** Synthetic endpoints and disposable emulator only; no private NAS configuration. */
public final class ConnectionTest {
    static int passed;static JSONArray checks=new JSONArray();
    static void check(boolean value,String name)throws Exception{Util.require(value,name);passed++;checks.put(name);System.out.println("PASS "+name);}
    interface Action{void run()throws Exception;}
    static void reject(Action a,String name)throws Exception{boolean rejected=false;try{a.run();}catch(Exception e){rejected=true;}check(rejected,name);}
    static JSONObject c(String external,String internal)throws Exception{return Util.obj("externalOrigin",external,"internalOrigin",internal,"path","/备份 % 目录/","wifiNames",new JSONArray().put("AndroidWifi").put("Home 5G"),"allowHttp",true,"username","test","password","test","vault","dual","zipDirs",new JSONArray(),"extraPaths",new JSONArray());}
    static Dav root(String origin)throws Exception{return new Dav(TransferBenchmark.config(origin+"/","unused"));}
    static JSONObject stats(String origin)throws Exception{return TransferBenchmark.stats(root(origin));}
    static String mode(Path state)throws Exception{return Util.read(state.resolve("connection.json")).getString("mode");}
    public static void main(String[] a){try{
        Util.require(Util.prop("ro.kernel.qemu").equals("1")&&Os.getuid()==0,"仅一次性 root 模拟器");
        Path base=Paths.get("/data/local/tmp/nasbackup-dual-test");Util.delete(base);Util.mkdir(base);
        JSONObject legacy=Settings.normalize(Util.obj("url",a[0]+"/备份%20%25%20目录/","allowHttp",true,"password","unchanged","vault","old"));
        check(legacy.getString("path").equals("/备份 % 目录/")&&!legacy.has("url")&&legacy.getString("password").equals("unchanged")&&legacy.getString("vault").equals("old"),"旧URL无损拆分并保留密码和vault");
        check(Settings.url(legacy.getString("externalOrigin"),legacy.getString("path")).endsWith("/%E5%A4%87%E4%BB%BD%20%25%20%E7%9B%AE%E5%BD%95/"),"Unicode空格百分号路径只编码一次");
        check(Settings.origin("https://NAS.example:5006/",false).equals("https://nas.example:5006"),"规范化主机和末尾斜线");
        check(Settings.origin("https://[::1]:5006",false).equals("https://[::1]:5006"),"IPv6地址");
        for(String s:Arrays.asList("https://u:p@nas.example","https://nas.example/folder","https://nas.example?q=1","https://nas.example#f","https://nas.example:70000","http://nas.example"))reject(()->Settings.origin(s,false),"拒绝无效origin "+s);
        for(String s:Arrays.asList("relative","/a/../b","/a/%2f/b","/a\\b","/a\nb"))reject(()->Settings.path(s),"拒绝无效共用路径");
        JSONObject config=c(a[0],a[1]);config.getJSONArray("wifiNames").put("AndroidWifi");check(Settings.normalize(config).getJSONArray("wifiNames").length()==2,"多个SSID精确去重");
        String sample="WifiInfo: SSID: \"Home, 5G\", BSSID: 00:11:22:33:44:55, Supplicant state: COMPLETED";
        check(Wifi.parse(sample).equals("Home, 5G"),"带空格逗号的SSID");check(Wifi.parse(sample.replace("COMPLETED","DISCONNECTED")).isEmpty(),"断开WiFi不当作当前网络");
        check(Wifi.parse(sample.replace("\"Home, 5G\"","<unknown ssid>")).isEmpty(),"未知SSID走默认地址");check(Wifi.current().equals("AndroidWifi"),"Android35实际cmd wifi status解析");
        check(Wifi.parse(sample.replace("COMPLETED","DISCONNECTED")+"\n"+sample.replace("Home, 5G","Primary")+", IsPrimary: 1").equals("Primary"),"多接口不误用断开SSID");
        check(Wifi.parse(sample+", IsPrimary: 0\n"+sample.replace("Home, 5G","Primary")+", IsPrimary: 1").equals("Primary"),"双WiFi只选择主接口");
        check(Wifi.parse(sample+"\n"+sample.replace("Home, 5G","Other")).isEmpty(),"无法确定主接口时回退默认地址");
        check(Connection.unavailable(new ConnectException())&&Connection.unavailable(new SocketTimeoutException())&&Connection.unavailable(new UnknownHostException()),"连接拒绝超时DNS允许网络回退");
        check(!Connection.unavailable(new javax.net.ssl.SSLHandshakeException("certificate"))&&!Connection.unavailable(new java.io.IOException("HTTP 503")),"证书和HTTP错误禁止网络回退");
        Path state=base.resolve("state");long before=stats(a[1]).getLong("PROPFIND");
        Dav external=Connection.open(state,config,"Unmatched");check(mode(state).equals("external"),"SSID未匹配使用外网");check(stats(a[1]).getLong("PROPFIND")==before,"未匹配时不探测内网或发送凭据");
        check(Util.read(state.resolve("connection.json")).getBoolean("peerPending"),"未验证入口明确标记待核验");
        Dav internal=Connection.open(state,config,"Home 5G");check(mode(state).equals("internal")&&internal.repositoryId.equals(external.repositoryId),"第二个SSID匹配内网且识别同库");
        check(Util.read(state.resolve("connection.json")).getBoolean("repositoryVerified"),"两入口同库核验完成");
        check(modeAfter(base.resolve("auto-ssid"),config).equals("internal"),"自动选择读取实际SSID");
        JSONObject unavailable=c(a[0],a[8]);Connection.open(state,unavailable,"AndroidWifi");check(mode(state).equals("external")&&Util.read(state.resolve("connection.json")).getString("reason").contains("回退"),"内网连接失败回退外网");
        Connection.open(state,config,"");check(mode(state).equals("external"),"未知SSID使用默认入口");
        for(int i=3;i<=7;i++){final int n=i;long count=stats(a[0]).getLong("PROPFIND");reject(()->Connection.open(base.resolve("reject-"+n),c(a[0],a[n]),"AndroidWifi"),"内网认证权限服务重定向证书错误不回退 "+i);check(stats(a[0]).getLong("PROPFIND")==count,"内网错误没有访问外网 "+i);}
        reject(()->Connection.open(state,c(a[0],a[2]),"AndroidWifi"),"同路径映射不同备份库立即拒绝");
        Path source=base.resolve("source"),module=source.resolve("data/adb/modules/dual");Util.mkdir(module);Files.write(module.resolve("module.prop"),"id=dual\nversion=1\nversionCode=1\n".getBytes(Util.UTF8));Files.write(module.resolve("settings"),"first".getBytes(Util.UTF8));
        Engine one=new Engine(state,new Inventory(source,true),config);one.backup();check(one.dav.uploadedObjects>0,"内网建立压缩备份基线");
        JSONObject wan=new JSONObject(config.toString()).put("wifiNames",new JSONArray());
        Engine two=new Engine(base.resolve("fresh-client"),new Inventory(source,true),wan);two.backup();check(two.dav.uploadedBytes==0,"外网新客户端复用内网全部分包零内容上传");
        Files.write(module.resolve("settings"),"changed".getBytes(Util.UTF8));Engine three=new Engine(state,new Inventory(source,true),config);JSONObject changed=three.backup();check(three.dav.uploadedObjects==1,"切回内网仅上传变化内容一个增量包");
        JSONObject manifest=three.manifest("dual",changed.getString("id"));Path target=base.resolve("target");Engine restoring=new Engine(base.resolve("restore"),new Inventory(target,true),config);Restore r=new Restore(restoring);r.prepare(manifest);r.early();r.finishApps();r.early();check(TransferBenchmark.verify(manifest,target)>0,"跨入口正式恢复内容和权限一致");
        byte[] marker=external.get(Connection.MARKER,4096);external.put(Connection.MARKER,Util.bytes(Util.obj("format",1,"id",UUID.randomUUID().toString())));
        reject(()->internal.checkRepository(),"同一任务提交前检测备份库身份改变");reject(()->Connection.open(state,config,"AndroidWifi"),"持久绑定拒绝备份库身份改变");external.put(Connection.MARKER,marker);
        // Conditional initialization must preserve the first writer's identity.
        Dav.Reply race=external.request("PUT",Connection.MARKER,new byte[]{1},Collections.singletonMap("If-None-Match","*"),4096);check(race.code==412&&Connection.marker(external).equals(internal.repositoryId),"并发初始化不能覆盖已有库标识");
        JSONObject report=Util.obj("passed",passed,"checks",checks,"physicalDeviceModified",false,"sharedRepositoryAcrossOrigins",true,"crossOriginUnchangedUploadBytes",two.dav.uploadedBytes);System.out.println(report);Util.delete(base);
    }catch(Throwable e){e.printStackTrace();System.exit(1);}}
    static String modeAfter(Path state,JSONObject config)throws Exception{Connection.open(state,config);return mode(state);}
}
