package dev.nasbackup;
import java.io.*;
import java.util.concurrent.*;
import java.util.regex.*;

final class Wifi {
    static String parse(String text){
        String candidate="";boolean ambiguous=false;
        for(String line:text.split("\n")){
            if(!line.trim().startsWith("WifiInfo:")||!line.contains("Supplicant state: COMPLETED"))continue;
            Matcher m=Pattern.compile("SSID: (.*?), BSSID: (?:[0-9a-fA-F:]{17}|<none>|null),").matcher(line);
            if(!m.find())continue;String name=m.group(1);if(name.startsWith("\"")&&name.endsWith("\"")&&name.length()>=2)name=name.substring(1,name.length()-1);
            if(name.equals("<unknown ssid>")||name.getBytes(Util.UTF8).length>32)name="";
            if(line.contains("IsPrimary: 1"))return name;
            if(line.contains("IsPrimary: 0"))continue;
            if(!candidate.isEmpty()&&!candidate.equals(name))ambiguous=true;candidate=name;
        }
        return ambiguous?"":candidate;
    }
    static String current(){
        try{
            Process p=new ProcessBuilder("/system/bin/cmd","wifi","status").redirectErrorStream(true).start();ByteArrayOutputStream out=new ByteArrayOutputStream();
            Thread reader=new Thread(()->{try(InputStream in=p.getInputStream()){byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1)if(out.size()+n<=32768)out.write(b,0,n);}catch(IOException ignored){}});reader.start();
            if(!p.waitFor(3,TimeUnit.SECONDS)){p.destroyForcibly();reader.join(1000);return "";}reader.join(1000);return p.exitValue()==0?parse(new String(out.toByteArray(),Util.UTF8)):"";
        }catch(Exception ex){return "";}
    }
}
