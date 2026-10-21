package dev.nasbackup;
import java.nio.file.*;
import org.json.*;
/** Reads installed modules; writes only its private temporary state and USB-loopback DAV fixture. */
public final class RealBackup {
    public static void main(String[] args)throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t,x)->{System.err.println(Util.safeError(x));System.exit(1);});
        Path state=Paths.get("/data/local/tmp/nasbackup-real-validation");Util.mkdir(state);
        JSONObject config=Util.obj("url","http://127.0.0.1:18792/","allowHttp",true,"username","test","password","test","vault","real-validation","zipDirs",new JSONArray().put("/storage/emulated/0/Download"));
        JSONObject result=new Engine(state,new Inventory(Paths.get("/"),false),config).backup();
        System.out.println(Util.obj("phase",result.getString("phase"),"files",result.getInt("files"),"uploadedFiles",result.getInt("uploadedFiles"),"uploadedBytes",result.getLong("uploadedBytes"),"warnings",result.getJSONArray("warnings").length()));
    }
}
