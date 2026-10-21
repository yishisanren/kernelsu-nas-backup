package dev.nasbackup;
import java.nio.file.*;
import org.json.*;
public final class Probe {
    public static void main(String[] args)throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t,e)->{e.printStackTrace(System.err);System.exit(1);});
        System.out.println("probe-start");
        if(args.length>0 && args[0].equals("hello"))return;
        if(args.length>0 && args[0].equals("sqlite")){
            android.database.sqlite.SQLiteDatabase d=Db.open(Paths.get("/data/local/tmp/nasbackup-sqlite-test.db"),true);
            System.out.println("sqlite-open");d.execSQL("CREATE TABLE IF NOT EXISTS test(v TEXT)");d.close();return;
        }
        if(args.length>0 && args[0].equals("schema")){
            android.database.sqlite.SQLiteDatabase d=Db.open(Paths.get("/data/adb/lspd/config/modules_config.db"),false);
            try(android.database.Cursor c=d.rawQuery("SELECT name,sql FROM sqlite_master WHERE type='table'",null)){while(c.moveToNext())System.out.println(c.getString(0)+" "+c.getString(1));}d.close();return;
        }
        if(args.length>0 && args[0].equals("coverage")){
            Inventory inventory=new Inventory(Paths.get("/"),false);JSONObject lsp=inventory.lsposed();JSONArray warnings=new JSONArray();java.util.Set<String> roots=new java.util.TreeSet<>();
            for(Path mod:inventory.modules())roots.add(mod.toString());roots.addAll(inventory.external(inventory.modules(),new JSONObject(),warnings));
            String misc=Lsp.misc(lsp);if(!misc.isEmpty())roots.add(misc);
            JSONArray pkgs=inventory.packages();for(int n=0;n<pkgs.length();n++){JSONObject pkg=pkgs.getJSONObject(n);roots.add("/data/user/"+pkg.getInt("user")+"/"+pkg.getString("package"));roots.add("/data/user_de/"+pkg.getInt("user")+"/"+pkg.getString("package"));}
            long bytes=0,files=0;JSONArray unsupported=new JSONArray();
            for(String root:roots){Path p=Paths.get(root);if(!Files.exists(p))continue;try(java.util.stream.Stream<Path> walk=Files.walk(p)){java.util.Iterator<Path> it=walk.iterator();while(it.hasNext()){Path f=it.next();JSONObject meta=Inventory.meta(f);files++;if(meta.getString("type").equals("file"))bytes+=meta.getLong("size");if(meta.getString("type").equals("unsupported"))unsupported.put(f.toString());}}}
            System.out.println(Util.obj("roots",roots.size(),"files",files,"bytes",bytes,"unsupported",unsupported,"miscPresent",!misc.isEmpty(),"external",new JSONArray(inventory.external(inventory.modules(),new JSONObject(),warnings))));return;
        }
        if(args.length>0 && args[0].equals("misc")){
            JSONObject lsp=new Inventory(Paths.get("/"),false).lsposed();JSONArray rows=lsp.getJSONObject("tables").getJSONArray("lspd_configs");
            for(int n=0;n<rows.length();n++){JSONObject row=rows.getJSONObject(n);if(row.getString("key_name").equals("misc_path")){byte[] b=java.util.Base64.getDecoder().decode(row.getJSONObject("data").getString("blob"));java.io.DataInputStream in=new java.io.DataInputStream(new java.io.ByteArrayInputStream(b));System.out.println(in.readInt()+" "+in.readUnsignedByte()+" "+in.readUTF()+" remain="+in.available());}}return;
        }
        Inventory i=new Inventory(Paths.get("/"),false);JSONObject l=i.lsposed();
        System.out.println(Util.obj("environment",i.environment(),"kernelModules",i.modules().size(),"lsposedVersion",l.optInt("version"),"lsposedModules",l.getJSONObject("tables").getJSONArray("modules").length(),"scopes",l.getJSONObject("tables").getJSONArray("scope").length(),"schema",l.getJSONArray("schema"),"apkModules",i.packages().length()));
    }
}
