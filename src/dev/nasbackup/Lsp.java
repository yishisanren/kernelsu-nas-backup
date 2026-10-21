package dev.nasbackup;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import org.json.*;

final class Lsp {
    static final Map<String,String> CURRENT=new LinkedHashMap<>(),LEGACY=new LinkedHashMap<>();
    static {
        CURRENT.put("modules","CREATE TABLE modules (module_pkg_name text PRIMARY KEY NOT NULL,apk_path text)");
        CURRENT.put("modules_state","CREATE TABLE modules_state(module_pkg_name text NOT NULL, user_id integer NOT NULL, enabled BOOLEAN DEFAULT 0,scope_request_blocked BOOLEAN DEFAULT 0,CHECK (enabled IN (0, 1)),CHECK (scope_request_blocked IN (0, 1)),PRIMARY KEY (module_pkg_name, user_id),CONSTRAINT modules_state_constraint  FOREIGN KEY (module_pkg_name)  REFERENCES modules (module_pkg_name)  ON DELETE CASCADE)");
        CURRENT.put("scope","CREATE TABLE scope (module_pkg_name text NOT NULL,app_pkg_name text NOT NULL,user_id integer NOT NULL,PRIMARY KEY (module_pkg_name, app_pkg_name, user_id),CONSTRAINT scope_module_constraint  FOREIGN KEY (module_pkg_name)  REFERENCES modules (module_pkg_name)  ON DELETE CASCADE)");
        CURRENT.put("module_configs","CREATE TABLE module_configs (module_pkg_name text NOT NULL,user_id integer NOT NULL,group_name text NOT NULL,key_name text NOT NULL,data blob NOT NULL,PRIMARY KEY (module_pkg_name, user_id, group_name, key_name),CONSTRAINT config_module_constraint  FOREIGN KEY (module_pkg_name)  REFERENCES modules (module_pkg_name)  ON DELETE CASCADE)");
        CURRENT.put("app_configs","CREATE TABLE app_configs (app_pkg_name text NOT NULL,data blob NOT NULL,PRIMARY KEY (app_pkg_name))");
        CURRENT.put("lspd_configs","CREATE TABLE lspd_configs (key_name text NOT NULL PRIMARY KEY,data blob NOT NULL)");
        LEGACY.put("modules","CREATE TABLE modules (mid integer PRIMARY KEY AUTOINCREMENT,module_pkg_name text NOT NULL UNIQUE,apk_path text NOT NULL,enabled BOOLEAN DEFAULT 0,CHECK (enabled IN (0, 1)))");
        LEGACY.put("scope","CREATE TABLE scope (mid integer NOT NULL,app_pkg_name text NOT NULL,user_id integer NOT NULL,PRIMARY KEY (mid, app_pkg_name, user_id),FOREIGN KEY (mid) REFERENCES modules (mid) ON DELETE CASCADE)");
        LEGACY.put("configs","CREATE TABLE configs (module_pkg_name text NOT NULL,user_id integer NOT NULL,`group` text NOT NULL,`key` text NOT NULL,data blob NOT NULL,PRIMARY KEY (module_pkg_name, user_id, `group`, `key`))");
    }
    static String norm(String s){return s.toLowerCase(Locale.ROOT).replaceAll("[\\s`\"]","");}
    static Set<String> missingApks(JSONObject snapshot,Set<String> available)throws Exception {
        Set<String> missing=new TreeSet<>();if(!snapshot.optBoolean("present"))return missing;
        JSONArray modules=snapshot.getJSONObject("tables").getJSONArray("modules");
        for(int i=0;i<modules.length();i++){String pkg=modules.getJSONObject(i).getString("module_pkg_name");Inventory.validPackage(pkg);if(!available.contains(pkg))missing.add(pkg);}return missing;
    }
    static void warnMissing(JSONObject snapshot,JSONArray packages,JSONArray warnings)throws Exception {
        Set<String> available=new HashSet<>(),existing=new HashSet<>();for(int i=0;i<packages.length();i++)available.add(packages.getJSONObject(i).getString("package"));for(int i=0;i<warnings.length();i++)existing.add(warnings.getString(i));
        for(String pkg:missingApks(snapshot,available)){String message=pkg+": 仅有 LSPosed 残留记录，无已安装 APK；保留配置和作用域，恢复后保持禁用。";if(existing.add(message))warnings.put(message);}
    }
    static String unavailablePath(String pkg)throws Exception {Inventory.validPackage(pkg);return "/data/app/nasbackup-unavailable/"+pkg+"/base.apk";}
    static JSONObject read(Path path)throws Exception {
        SQLiteDatabase db=Db.open(path,false);
        try {
            db.execSQL("BEGIN DEFERRED");JSONObject tables=new JSONObject();JSONArray schema=new JSONArray();Map<String,String> found=new LinkedHashMap<>();
            try(Cursor c=db.rawQuery("SELECT name,sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name!='android_metadata' ORDER BY name",null)){while(c.moveToNext())found.put(c.getString(0),c.getString(1));}
            Map<String,String> supported=found.containsKey("modules_state")?CURRENT:LEGACY;
            Util.require(found.keySet().equals(supported.keySet()),"LSPosed 数据表未适配");
            for(String table:supported.keySet()) {
                // Snapshot carries original schema for audit; restoration uses only these built-in DDLs.
                if(supported==CURRENT)Util.require(norm(found.get(table)).equals(norm(supported.get(table))),"LSPosed 表结构已变化: "+table);
                JSONArray rows=new JSONArray();try(Cursor c=db.rawQuery("SELECT * FROM "+table,null)) {
                    while(c.moveToNext()){JSONObject row=new JSONObject();for(int j=0;j<c.getColumnCount();j++){
                        Object v=c.isNull(j)?JSONObject.NULL:c.getType(j)==Cursor.FIELD_TYPE_BLOB?Util.obj("blob",Base64.getEncoder().encodeToString(c.getBlob(j))):c.getType(j)==Cursor.FIELD_TYPE_INTEGER?c.getLong(j):c.getType(j)==Cursor.FIELD_TYPE_FLOAT?c.getDouble(j):c.getString(j);row.put(c.getColumnName(j),v);
                    }rows.put(row);}
                }tables.put(table,rows);schema.put(Util.obj("name",table,"sql",found.get(table)));
            }
            JSONObject result=Util.obj("present",true,"variant",supported==CURRENT?"modern":"legacy","version",db.getVersion(),"tables",tables,"schema",schema);
            db.execSQL("COMMIT");return result;
        }finally{db.close();}
    }
    static void write(JSONObject snapshot,Path target,Map<String,String> apkPaths)throws Exception {
        Util.require(!Files.exists(target),"恢复数据库目标已存在");
        String variant=snapshot.getString("variant");Util.require(variant.equals("modern")||variant.equals("legacy"),"未知 LSPosed 数据格式");
        Map<String,String> ddl=variant.equals("modern")?CURRENT:LEGACY;
        JSONObject tables=snapshot.getJSONObject("tables");Set<String> names=new HashSet<>();tables.keys().forEachRemaining(names::add);Util.require(names.equals(ddl.keySet()),"LSPosed 表清单不完整");
        Set<String> missing=missingApks(snapshot,apkPaths.keySet());
        Util.mkdir(target.getParent());SQLiteDatabase db=Db.open(target,true);
        try {db.beginTransaction();for(String table:ddl.keySet())db.execSQL(ddl.get(table));
            for(String table:ddl.keySet()) {
                Set<String> cols=new HashSet<>();try(Cursor c=db.rawQuery("PRAGMA table_info("+table+")",null)){while(c.moveToNext())cols.add(c.getString(1));}
                JSONArray rows=tables.getJSONArray(table);for(int i=0;i<rows.length();i++){
                    JSONObject row=rows.getJSONObject(i);List<String> columns=new ArrayList<>();List<Object> values=new ArrayList<>();Iterator<String> keys=row.keys();
                    while(keys.hasNext()){String k=keys.next();Util.require(cols.contains(k),"未知 LSPosed 字段");Object v=row.get(k);
                        if(k.equals("apk_path")){String pkg=row.getString("module_pkg_name");v=missing.contains(pkg)?unavailablePath(pkg):apkPaths.get(pkg);}
                        if(k.equals("enabled")&&(table.equals("modules")||table.equals("modules_state"))&&missing.contains(row.getString("module_pkg_name")))v=0;
                        columns.add("\""+k+"\"");values.add(v==JSONObject.NULL?null:v instanceof JSONObject?Base64.getDecoder().decode(((JSONObject)v).getString("blob")):v);
                    }
                    Util.require(!columns.isEmpty(),"空 LSPosed 数据记录");
                    // Columns are checked against built-in DDL; quoting also supports legacy `group`/`key` names.
                    db.execSQL("INSERT INTO \""+table+"\" ("+String.join(",",columns)+") VALUES ("+String.join(",",Collections.nCopies(values.size(),"?"))+")",values.toArray());
                }
            }db.setVersion(snapshot.getInt("version"));db.setTransactionSuccessful();
        }finally{if(db.inTransaction())db.endTransaction();db.close();}
    }
    static String misc(JSONObject snapshot)throws Exception {
        if(!snapshot.optBoolean("present"))return "";
        JSONObject tables=snapshot.getJSONObject("tables");JSONArray rows=tables.optJSONArray("lspd_configs");String key="key_name";
        if(rows==null){rows=tables.optJSONArray("configs");key="key";}if(rows==null)return "";
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!row.optString(key).equals("misc_path"))continue;
            byte[] b=Base64.getDecoder().decode(row.getJSONObject("data").getString("blob"));
            // Decode only a serialized String, never ObjectInputStream with arbitrary classes.
            try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(b))){Util.require(in.readInt()==0xaced0005&&in.readUnsignedByte()==0x74,"LSPosed misc_path 编码未适配");String s=in.readUTF();Util.require(in.available()==0&&validMisc(s),"LSPosed misc_path 无效");return s;}
        }return "";
    }
    static boolean validMisc(String s){return s.matches("/data/misc/(?:apexdata/)?[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");}
}
