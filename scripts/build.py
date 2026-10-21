#!/usr/bin/env python3
"""Build release and isolated-test dex independently, with pinned Maven dependencies."""
import hashlib, json, os, pathlib, shutil, subprocess, zipfile
from dependencies import dependencies
ROOT = pathlib.Path(__file__).resolve().parents[1]
JAVA = pathlib.Path(os.environ.get('JAVA_HOME', '/path/to/jdk21'))
SDK = pathlib.Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', '/path/to/android-sdk')))
BUILD = ROOT / 'build'
DIST = ROOT / 'dist'
VERSION='0.1.0-beta.2'

def entry(z,name,data,mode=0o644):
    info=zipfile.ZipInfo(name,(2026,10,21,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
    info.create_system=3;info.external_attr=(0o100000|mode)<<16
    z.writestr(info,data)
def jar(folder,target):
    with zipfile.ZipFile(target,'w') as z:
        for p in sorted(folder.rglob('*.class')):entry(z,str(p.relative_to(folder)),p.read_bytes())
def main():
    if not (JAVA/'bin/javac').is_file() or not (SDK/'platforms/android-36/android.jar').is_file():
        raise SystemExit('Set JAVA_HOME to JDK 21 and ANDROID_HOME to an SDK containing Android Platform 36.')
    BUILD.mkdir(exist_ok=True);DIST.mkdir(exist_ok=True)
    deps=dependencies();android=SDK/'platforms/android-36/android.jar'
    classes=BUILD/'classes';testclasses=BUILD/'test-classes'
    for folder in [classes,testclasses]:
        if folder.exists():shutil.rmtree(folder)
        folder.mkdir()
    javac=[str(JAVA/'bin/javac'),'-encoding','UTF-8','--release','8','-Xlint:unchecked','-Xlint:-options']
    subprocess.run(javac+['-classpath',os.pathsep.join(map(str,[android,*deps])),'-d',str(classes),*map(str,sorted(ROOT.glob('src/**/*.java')))],check=True)
    mainjar=BUILD/'classes.jar';jar(classes,mainjar)
    subprocess.run(javac+['-classpath',os.pathsep.join(map(str,[android,mainjar,*deps])),'-d',str(testclasses),*map(str,sorted(ROOT.glob('tests/java/**/*.java')))],check=True)
    testjar=BUILD/'test-classes.jar';jar(testclasses,testjar)
    for name,inputs in [('nasbackup',[mainjar,*deps]),('nasbackup-test',[mainjar,testjar,*deps])]:
        dex=BUILD/name
        if dex.exists():shutil.rmtree(dex)
        dex.mkdir()
        subprocess.run([str(JAVA/'bin/java'),'-cp',str(SDK/'build-tools/36.0.0/lib/d8.jar'),'com.android.tools.r8.D8','--min-api','28','--lib',str(android),'--output',str(dex),*map(str,inputs)],check=True)
        with zipfile.ZipFile(BUILD/(name+'.jar'),'w') as z:
            for p in sorted(dex.glob('classes*.dex')):entry(z,p.name,p.read_bytes())
    target=DIST/('nas-module-backup-'+VERSION+'.zip')
    with zipfile.ZipFile(target,'w') as z:
        for p in sorted((ROOT/'module').rglob('*')):
            if p.is_file():entry(z,str(p.relative_to(ROOT/'module')),p.read_bytes(),0o755 if p.suffix=='.sh' or p.name=='nasbackup' else 0o644)
        entry(z,'lib/nasbackup.jar',(BUILD/'nasbackup.jar').read_bytes())
    result={'zip':str(target),'bytes':target.stat().st_size,'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'testRuntime':str(BUILD/'nasbackup-test.jar')}
    (BUILD/'build-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print(json.dumps(result,ensure_ascii=False))
if __name__=='__main__':main()
