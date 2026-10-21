package dev.nasbackup;
import org.json.*;
import java.nio.file.*;
public final class ApkTest {
    public static void main(String[] args)throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t,x)->{x.printStackTrace();System.exit(1);});
        String name="dev.nasbackup.fixture";Util.require(Restore.apkPaths(name,0).isEmpty(),"Fixture already installed; refuse to replace");
        Path base=Paths.get("/data/local/tmp/nasbackup-apk-test"),stage=base.resolve("stage");Util.mkdir(stage.resolve("roots/apk"));Files.copy(Paths.get("/data/local/tmp/nasbackup-fixture.apk"),stage.resolve("roots/apk/payload"),StandardCopyOption.REPLACE_EXISTING);
        JSONObject pkg=Util.obj("package",name,"user",0);JSONObject manifest=Util.obj("roots",new JSONArray().put(Util.obj("kind","apk","key","apk","detail",pkg)));
        Util.mkdir(stage.resolve("roots/split"));Files.copy(Paths.get("/data/local/tmp/nasbackup-fixture-split.apk"),stage.resolve("roots/split/payload"),StandardCopyOption.REPLACE_EXISTING);manifest.getJSONArray("roots").put(Util.obj("kind","apk","key","split","detail",pkg));
        JSONObject config=Util.obj("url","http://127.0.0.1:18791/","allowHttp",true,"username","test","password","test");
        Restore restore=new Restore(new Engine(base,new Inventory(Paths.get("/"),false),config));
        JSONObject badPkg=Util.obj("package","dev.nasbackup.wrongfixture","user",0);JSONObject badManifest=new JSONObject(manifest.toString());for(int i=0;i<badManifest.getJSONArray("roots").length();i++)badManifest.getJSONArray("roots").getJSONObject(i).put("detail",badPkg);
        boolean blocked=false;try{restore.install(badManifest,badPkg,stage);}catch(Exception expected){blocked=true;}Util.require(blocked&&Restore.apkPaths(name,0).isEmpty(),"Expected package name was not enforced");
        try {
            restore.install(manifest,pkg,stage);int newUid=Restore.uid(name,0),oldUid=newUid==10001?10002:10001;
            Util.require(Restore.apkPaths(name,0).size()==2&&newUid>10000,"Split APK install verification failed");Util.require(Util.unlocked(0),"User unlock query failed");
            Path ce=Paths.get("/data/user/0/"+name);long inode=android.system.Os.stat(ce.toString()).st_ino;
            Files.write(ce.resolve("before.txt"),"before".getBytes(Util.UTF8));android.system.Os.chown(ce.resolve("before.txt").toString(),newUid,newUid);
            JSONArray roots=new JSONArray(),entries=new JSONArray();
            for(String kind:new String[]{"app_ce","app_de","app_external"}){
                String dest=kind.equals("app_ce")?"/data/user/0/"+name:kind.equals("app_de")?"/data/user_de/0/"+name:"/data/media/0/Android/data/"+name;
                Path content=base.resolve("restore-stage/roots").resolve(kind);Util.mkdir(content);Files.write(content.resolve("restored.txt"),kind.getBytes(Util.UTF8));android.system.Os.chown(content.resolve("restored.txt").toString(),oldUid,oldUid);
                roots.put(Util.obj("key",kind,"kind",kind,"destination",dest,"detail",Util.obj("package",name,"user",0,"uid",oldUid)));
                entries.put(Inventory.meta(content).put("path","").put("root",kind).put("uid",oldUid).put("gid",oldUid));entries.put(Inventory.meta(content.resolve("restored.txt")).put("path","restored.txt").put("root",kind));
            }
            Util.write(base.resolve("restore-stage/manifest.json"),Util.obj("roots",roots,"entries",entries));
            restore.save(Util.obj("phase","awaiting-unlock","appMoves",new JSONArray(),"moves",new JSONArray(),"newPackages",new JSONArray(),"uidMap",Util.obj(Integer.toString(oldUid),newUid)));
            restore.finishApps();Util.require(Files.exists(ce.resolve("restored.txt"))&&!Files.exists(ce.resolve("before.txt")),"Native CE restore failed");
            Util.require(android.system.Os.stat(ce.toString()).st_ino==inode,"FBE root inode changed");Util.require(android.system.Os.stat(ce.resolve("restored.txt").toString()).st_uid==newUid,"UID mapping failed");
            JSONObject journal=restore.status();journal.put("phase","rollback-unlock");restore.save(journal);restore.rollbackApps();Util.require(Files.exists(ce.resolve("before.txt"))&&!Files.exists(ce.resolve("restored.txt")),"Native app rollback failed");
            System.out.println(Util.obj("splitApkSessionInstall",true,"expectedPackageMismatchRejected",true,"uidResolved",true,"userUnlockApi",true,"nativeCeDeExternalRestore",true,"ceRootInodePreserved",true,"uidRemapped",true,"nativeAppRollback",true));
        }
        finally{if(!Restore.apkPaths(name,0).isEmpty())Util.require(Util.run("/system/bin/pm","uninstall",name).contains("Success"),"Fixture removal failed");}
        Util.delete(base);
    }
}
