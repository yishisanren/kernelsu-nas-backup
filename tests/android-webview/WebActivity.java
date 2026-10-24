package dev.nasbackup.webviewfixture;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import org.json.JSONObject;

/** Emulator-only native WebView. Root commands run through an owned loopback test server. */
public final class WebActivity extends Activity {
    private final ExecutorService executor=Executors.newFixedThreadPool(2);
    private WebView web;
    private String origin;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        origin=getIntent().getStringExtra("origin");
        if(!android.os.Build.HARDWARE.equals("ranchu")||origin==null||!origin.matches("http://10\\.0\\.2\\.2:[0-9]{1,5}/[a-f0-9]{32}/")){finish();return;}
        web=new WebView(this);setContentView(web);web.getSettings().setJavaScriptEnabled(true);
        WebView.setWebContentsDebuggingEnabled(true);
        web.addJavascriptInterface(new Bridge(),"ksu");web.loadUrl(origin+"index.html");
    }
    final class Bridge {
        @JavascriptInterface public void exec(String command,String options,String callback){
            if(!callback.matches("nas_cb_[0-9]+_[0-9]+"))return;
            executor.execute(()->{
                int code=1;String out="",error="Fixture transport failed";HttpURLConnection c=null;
                try{
                    c=(HttpURLConnection)new URL(origin+"exec").openConnection();c.setRequestMethod("POST");c.setConnectTimeout(5000);c.setReadTimeout(90000);c.setDoOutput(true);
                    byte[] request=new JSONObject().put("command",command).toString().getBytes(StandardCharsets.UTF_8);
                    c.setFixedLengthStreamingMode(request.length);c.getOutputStream().write(request);
                    ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] block=new byte[8192];int count;
                    try(java.io.InputStream in=c.getInputStream()){while((count=in.read(block))!=-1){if(buffer.size()+count>4*1024*1024)throw new Exception("Fixture response limit");buffer.write(block,0,count);}}
                    JSONObject result=new JSONObject(new String(buffer.toByteArray(),StandardCharsets.UTF_8));code=result.getInt("code");out=result.getString("out");error=result.getString("err");
                }catch(Exception e){error=e.getClass().getSimpleName();}finally{if(c!=null)c.disconnect();}
                final String script="window["+JSONObject.quote(callback)+"]("+code+","+JSONObject.quote(out)+","+JSONObject.quote(error)+")";
                runOnUiThread(()->web.evaluateJavascript(script,null));
            });
        }
        @JavascriptInterface public String geometry(){int[] p=new int[2];web.getLocationOnScreen(p);return "{\"x\":"+p[0]+",\"y\":"+p[1]+",\"width\":"+web.getWidth()+",\"height\":"+web.getHeight()+"}";}
    }
    @Override public void onDestroy(){executor.shutdownNow();if(web!=null){web.removeJavascriptInterface("ksu");web.destroy();}super.onDestroy();}
}
