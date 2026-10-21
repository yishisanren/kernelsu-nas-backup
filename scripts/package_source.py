#!/usr/bin/env python3
import hashlib,json,pathlib,zipfile
from build import ROOT,DIST,VERSION,entry
DIST.mkdir(exist_ok=True)
target=DIST/('nas-module-backup-'+VERSION+'-source.zip')
roots=['src','module','scripts','tests','docs']
files=[ROOT/name for name in ['README.md','THIRD_PARTY.md','dependencies.lock.json','.gitignore']]
for folder in roots:files.extend(p for p in (ROOT/folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts and p.suffix!='.pyc')
with zipfile.ZipFile(target,'w') as z:
    for p in sorted(files):entry(z,str(p.relative_to(ROOT)),p.read_bytes())
checks={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(DIST.glob('*.zip'))}
(DIST/'SHA256SUMS.txt').write_text(''.join(sha+'  '+name+'\n' for name,sha in checks.items()))
print(json.dumps(checks,ensure_ascii=False))
