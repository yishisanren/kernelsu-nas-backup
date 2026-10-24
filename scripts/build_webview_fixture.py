"""Build the native Android WebView acceptance harness, never a release payload."""
import os,subprocess,zipfile
from build import ROOT,BUILD,JAVA,SDK
out=BUILD/'webview-fixture';out.mkdir(parents=True,exist_ok=True)
android=SDK/'platforms/android-36/android.jar';tools=SDK/'build-tools/36.0.0';env=dict(os.environ,JAVA_HOME=str(JAVA))
classes=out/'classes';classes.mkdir(exist_ok=True)
subprocess.run([str(JAVA/'bin/javac'),'--release','8','-classpath',str(android),'-d',str(classes),str(ROOT/'tests/android-webview/WebActivity.java')],check=True)
dex=out/'dex';dex.mkdir(exist_ok=True)
subprocess.run([str(JAVA/'bin/java'),'-cp',str(tools/'lib/d8.jar'),'com.android.tools.r8.D8','--min-api','28','--lib',str(android),'--output',str(dex),*map(str,sorted(classes.rglob('*.class')))],check=True)
subprocess.run([str(tools/'aapt2'),'link','--manifest',str(ROOT/'tests/android-webview/AndroidManifest.xml'),'-I',str(android),'-o',str(out/'unsigned.apk')],check=True)
with zipfile.ZipFile(out/'unsigned.apk','a') as archive:
    for p in sorted(dex.glob('classes*.dex')):archive.write(p,p.name)
key=out/'test.p12'
if not key.exists():subprocess.run([str(JAVA/'bin/keytool'),'-genkeypair','-keystore',str(key),'-storepass','fixture-only','-keypass','fixture-only','-alias','fixture','-dname','CN=Disposable WebView Test','-keyalg','RSA','-validity','10'],check=True)
subprocess.run([str(tools/'apksigner'),'sign','--ks',str(key),'--ks-pass','pass:fixture-only','--out',str(out/'webview.apk'),str(out/'unsigned.apk')],check=True,env=env)
print(out/'webview.apk')
