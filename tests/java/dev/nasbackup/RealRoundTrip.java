package dev.nasbackup;

import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Reconstructs real LSPosed state only in a private temporary database. */
public final class RealRoundTrip {
    static String canonical(Object value)throws Exception {
        if(value instanceof JSONObject){
            JSONObject object=(JSONObject)value;List<String> keys=new ArrayList<>();object.keys().forEachRemaining(keys::add);Collections.sort(keys);
            StringBuilder out=new StringBuilder("{");for(String key:keys)out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key))).append(',');return out.append('}').toString();
        }
        return value==JSONObject.NULL?"null":value instanceof String?JSONObject.quote((String)value):value.toString();
    }
    static List<String> rows(JSONArray values)throws Exception {List<String> out=new ArrayList<>();for(int i=0;i<values.length();i++)out.add(canonical(values.get(i)));Collections.sort(out);return out;}
    public static void main(String[] args)throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t,x)->{System.err.println(Util.safeError(x));System.exit(1);});
        Path state=Paths.get("/data/local/tmp/nasbackup-real-validation");
        JSONObject config=Util.obj("url","http://127.0.0.1:18792/","allowHttp",true,"username","test","password","test","vault","real-validation");
        Engine engine=new Engine(state,new Inventory(Paths.get("/"),false),config);
        String id=Util.read(state.resolve("last-backup.json")).getString("id");
        JSONObject manifest=engine.manifest("real-validation",id);Engine.validate(manifest);
        JSONObject original=manifest.getJSONObject("lsposed"),expected=new JSONObject(original.toString());
        JSONArray modules=expected.getJSONObject("tables").getJSONArray("modules"),packages=manifest.getJSONArray("packages");Map<String,String> paths=new HashMap<>();
        for(int i=0;i<packages.length();i++){String pkg=packages.getJSONObject(i).getString("package");paths.put(pkg,"/data/app/nasbackup-validation/"+pkg+"/base.apk");}
        Set<String> missing=Lsp.missingApks(original,paths.keySet());
        for(int i=0;i<modules.length();i++){JSONObject row=modules.getJSONObject(i);String pkg=row.getString("module_pkg_name");row.put("apk_path",missing.contains(pkg)?Lsp.unavailablePath(pkg):paths.get(pkg));}
        JSONArray states=expected.getJSONObject("tables").getJSONArray(expected.getString("variant").equals("modern")?"modules_state":"modules");for(int i=0;i<states.length();i++){JSONObject row=states.getJSONObject(i);if(missing.contains(row.getString("module_pkg_name")))row.put("enabled",0);}
        Path target=state.resolve("roundtrip.db");Util.require(!Files.exists(target),"隔离验证目标已存在");
        try {
            Lsp.write(original,target,paths);JSONObject actual=Lsp.read(target);
            Util.require(actual.getInt("version")==expected.getInt("version"),"数据库版本不一致");
            JSONObject tables=expected.getJSONObject("tables");Iterator<String> names=tables.keys();int tableCount=0,rowCount=0;
            while(names.hasNext()){String name=names.next();Util.require(rows(tables.getJSONArray(name)).equals(rows(actual.getJSONObject("tables").getJSONArray(name))),"隔离数据库往返不一致");tableCount++;rowCount+=tables.getJSONArray(name).length();}
            System.out.println(Util.obj("passed",true,"tables",tableCount,"rows",rowCount,"apkPathsRemapped",paths.size(),"missingApkRecordsPreservedDisabled",missing.size(),"productionDatabaseChanged",false));
        }finally{for(String suffix:Arrays.asList("","-wal","-shm","-journal"))Files.deleteIfExists(Paths.get(target.toString()+suffix));}
    }
}
