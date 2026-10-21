import os,pathlib,subprocess
from build import ROOT,JAVA,SDK,BUILD
out=BUILD/'fixture';out.mkdir(parents=True,exist_ok=True)
env=dict(os.environ,JAVA_HOME=str(JAVA))
subprocess.run([str(SDK/'build-tools/36.0.0/aapt2'),'link','--manifest',str(ROOT/'tests/AndroidManifest.xml'),'-I',str(SDK/'platforms/android-36/android.jar'),'-o',str(out/'unsigned.apk')],check=True,env=env)
key=out/'test.p12'
if not key.exists():subprocess.run([str(JAVA/'bin/keytool'),'-genkeypair','-keystore',str(key),'-storepass','fixture-only','-keypass','fixture-only','-alias','fixture','-dname','CN=Disposable NAS Backup Test','-keyalg','RSA','-validity','10'],check=True,env=env)
subprocess.run([str(SDK/'build-tools/36.0.0/apksigner'),'sign','--ks',str(key),'--ks-pass','pass:fixture-only','--out',str(out/'fixture.apk'),str(out/'unsigned.apk')],check=True,env=env)
print(out/'fixture.apk')
subprocess.run([str(SDK/'build-tools/36.0.0/aapt2'),'link','--manifest',str(ROOT/'tests/AndroidManifest-split.xml'),'-I',str(SDK/'platforms/android-36/android.jar'),'-o',str(out/'split-unsigned.apk')],check=True,env=env)
subprocess.run([str(SDK/'build-tools/36.0.0/apksigner'),'sign','--ks',str(key),'--ks-pass','pass:fixture-only','--out',str(out/'split.apk'),str(out/'split-unsigned.apk')],check=True,env=env)
