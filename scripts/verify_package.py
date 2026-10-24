#!/usr/bin/env python3
"""Validate release ZIP structure, source inclusion, script syntax, and dex isolation."""
import hashlib,io,json,pathlib,subprocess,tempfile,zipfile,sys
from build import ROOT,DIST,VERSION
target=DIST/('nas-module-backup-'+VERSION+'.zip')
reproduced=False
if '--rebuild' in sys.argv:
    previous=hashlib.sha256(target.read_bytes()).hexdigest()
    subprocess.run([sys.executable,str(ROOT/'scripts/build.py')],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    assert hashlib.sha256(target.read_bytes()).hexdigest()==previous
    reproduced=True
with zipfile.ZipFile(target) as z:
    assert z.testzip() is None
    names=set(z.namelist())
    required={'module.prop','customize.sh','service.sh','post-fs-data.sh','action.sh','bin/nasbackup','lib/nasbackup.jar','webroot/index.html','webroot/app.js','webroot/style.css','licenses/APACHE-2.0.txt'}
    assert required<=names
    assert not any(n.startswith('tests/') or n.endswith('.apk') for n in names), 'Test APKs must not enter the installable module'
    assert not any(n.startswith('/') or '..' in pathlib.PurePosixPath(n).parts for n in names)
    assert 'id=nas_module_backup' in z.read('module.prop').decode()
    for p in (ROOT/'module').rglob('*'):
        if p.is_file():assert z.read(str(p.relative_to(ROOT/'module')))==p.read_bytes()
    for name in names:
        if name.endswith('.sh') or name=='bin/nasbackup':
            subprocess.run(['sh','-n'],input=z.read(name),check=True)
            if name!='uninstall.sh':assert (z.getinfo(name).external_attr>>16)&0o111
    with zipfile.ZipFile(io.BytesIO(z.read('lib/nasbackup.jar'))) as jar:
        dex=b''.join(jar.read(n) for n in jar.namelist())
        assert b'Ldev/nasbackup/Main;' in dex
        for source in (ROOT/'tests/java/dev/nasbackup').glob('*.java'):
            assert ('Ldev/nasbackup/'+source.stem+';').encode() not in dex
        assert b'Ldev/nasbackup/webviewfixture/' not in dex
result={'zipCrc':True,'requiredEntries':True,'scriptsParse':True,'sourceMatches':True,'testClassesExcluded':True,'reproducibleBuildVerified':reproduced,'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'bytes':target.stat().st_size}
folder=ROOT/'docs/evidence';folder.mkdir(parents=True,exist_ok=True);(folder/'package.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
