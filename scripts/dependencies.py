"""Fetch fixed Maven coordinates; subsequent builds check the committed SHA-256 lock."""
import hashlib,json,pathlib,urllib.request
ROOT=pathlib.Path(__file__).resolve().parents[1]
COORDS={
 'okhttp-4.12.0.jar':'com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar',
 'okio-jvm-3.6.0.jar':'com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar',
 'kotlin-stdlib-1.9.10.jar':'org/jetbrains/kotlin/kotlin-stdlib/1.9.10/kotlin-stdlib-1.9.10.jar',
 'annotations-13.0.jar':'org/jetbrains/annotations/13.0/annotations-13.0.jar',
}
def dependencies():
    folder=ROOT/'build/deps';folder.mkdir(parents=True,exist_ok=True)
    lock=ROOT/'dependencies.lock.json'
    expected=json.loads(lock.read_text()) if lock.exists() else {}
    hashes={}; paths=[]
    for name,coord in COORDS.items():
        p=folder/name
        if not p.exists():
            with urllib.request.urlopen('https://repo.maven.apache.org/maven2/'+coord,timeout=60) as r:p.write_bytes(r.read())
        sha=hashlib.sha256(p.read_bytes()).hexdigest()
        if expected and expected.get(name)!=sha:raise RuntimeError('Dependency checksum mismatch: '+name)
        hashes[name]=sha;paths.append(p)
    if not expected:raise RuntimeError('Create dependencies.lock.json from reviewed downloads: '+json.dumps(hashes))
    return paths
if __name__=='__main__':dependencies()
