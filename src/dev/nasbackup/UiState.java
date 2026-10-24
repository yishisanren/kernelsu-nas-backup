package dev.nasbackup;
import org.json.*;
import java.nio.file.*;

/** Root-only, atomically published view data; never caches the saved password. */
final class UiState {
    static JSONObject publicConfig(JSONObject c)throws Exception {
        JSONObject out=new JSONObject(c.toString());boolean saved=!out.optString("password").isEmpty();out.remove("password");return out.put("passwordSaved",saved).put("toolVersion",Util.VERSION);
    }
    static void config(Path state,JSONObject c,String sourceHash)throws Exception {
        Path p=state.resolve("ui-config.cache");String key=sourceHash+":"+Util.VERSION;
        if(Files.exists(p)){String old=new String(Files.readAllBytes(p),Util.UTF8);if(old.startsWith(key+"\n"))return;}
        Util.write(p,(key+"\n"+publicConfig(c).toString()).getBytes(Util.UTF8));
    }
    static JSONObject restore(Path state,JSONObject journal)throws Exception {
        JSONObject result=Util.obj("phase",journal.optString("phase","none"),"error",journal.optString("error"));Util.write(state.resolve("restore-ui.json"),result);return result;
    }
}
