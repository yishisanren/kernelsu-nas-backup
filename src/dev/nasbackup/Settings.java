package dev.nasbackup;
import java.net.URI;
import java.util.*;
import org.json.*;

/** One decoded directory path is shared by every origin. */
final class Settings {
    static String origin(String value,boolean http)throws Exception {
        if(value.isEmpty())return "";URI u=new URI(value);
        Util.require(("https".equals(u.getScheme())||("http".equals(u.getScheme())&&http))&&u.getHost()!=null&&u.getUserInfo()==null&&u.getQuery()==null&&u.getFragment()==null,"地址需为 HTTPS 主机和端口；HTTP 需明确开启");
        Util.require(u.getPath()==null||u.getPath().isEmpty()||u.getPath().equals("/"),"地址不能包含目录，请填写下方的共用路径");
        Util.require(u.getPort()==-1||(u.getPort()>0&&u.getPort()<=65535),"端口无效");
        return new URI(u.getScheme(),null,u.getHost().toLowerCase(Locale.ROOT),u.getPort(),null,null,null).toASCIIString();
    }
    static String path(String value)throws Exception {
        String p=value.trim();if(p.isEmpty())p="/";
        Util.require(p.startsWith("/")&&!p.contains("\\")&&!p.matches("(?s).*[\\x00-\\x1f\\x7f].*"),"共用路径必须是以 / 开头的目录");
        Util.require(!p.matches("(?is).*%(?:2e|2f|5c).*"),"路径请使用未编码的目录名，不能包含编码的点或分隔符");
        for(String s:p.split("/"))Util.require(!s.equals(".")&&!s.equals(".."),"共用路径不能包含 . 或 ..");
        return p.replaceAll("/{2,}","/").replaceAll("/+$","")+"/";
    }
    static JSONObject normalize(JSONObject source)throws Exception {
        JSONObject c=new JSONObject(source.toString());boolean http=c.optBoolean("allowHttp");
        if(c.has("url")&&!c.has("externalOrigin")&&!c.has("internalOrigin")){
            URI old=new URI(c.getString("url"));Util.require(old.getHost()!=null&&old.getUserInfo()==null&&old.getQuery()==null&&old.getFragment()==null,"旧 WebDAV 地址无效");
            c.put("externalOrigin",new URI(old.getScheme(),null,old.getHost(),old.getPort(),null,null,null).toASCIIString());c.put("path",old.getPath());
        }
        c.remove("url");c.put("externalOrigin",origin(c.optString("externalOrigin").trim(),http));c.put("internalOrigin",origin(c.optString("internalOrigin").trim(),http));c.put("path",path(c.optString("path","/")));
        Util.require(!c.optString("externalOrigin").isEmpty()||c.optString("internalOrigin").isEmpty(),"请先填写外网或默认地址");Util.require(!c.optString("username").contains(":"),"用户名不能包含冒号");
        JSONArray names=c.optJSONArray("wifiNames"),out=new JSONArray();Set<String> seen=new LinkedHashSet<>();
        if(names!=null){Util.require(names.length()<=64,"最多保存 64 个 Wi-Fi 名称");for(int i=0;i<names.length();i++){
            String n=names.getString(i);Util.require(!n.isEmpty()&&n.getBytes(Util.UTF8).length<=32&&!n.matches("(?s).*[\\x00-\\x1f\\x7f].*"),"Wi-Fi 名称需为 1～32 字节，不能包含控制字符");if(seen.add(n))out.put(n);
        }}c.put("wifiNames",out);return c;
    }
    static boolean configured(JSONObject c){return !c.optString("externalOrigin").isEmpty()||c.has("url");}
    static String url(String origin,String path)throws Exception {URI u=new URI(origin);return new URI(u.getScheme(),u.getRawAuthority(),path,null,null).toASCIIString();}
    static JSONObject at(JSONObject c,String origin)throws Exception{return new JSONObject(c.toString()).put("url",url(origin,c.getString("path")));}
}
