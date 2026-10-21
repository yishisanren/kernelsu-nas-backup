#!/usr/bin/env python3
"""Audit an explicitly authorized private fixture; emit aggregate evidence only."""
import argparse, collections, hashlib, json, pathlib, stat


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def audit(root):
    assert root.is_dir() and stat.S_IMODE(root.stat().st_mode) == 0o700
    objects = {}
    for path in root.rglob('*'):
        assert not path.is_symlink(), 'Unexpected fixture symlink'
        assert stat.S_IMODE(path.stat().st_mode) & 0o077 == 0, 'Fixture permissions too broad'
        if path.is_file() and path.parent.parent.name == 'objects':
            assert len(path.name) == 64 and digest(path) == path.name, 'Object digest mismatch'
            objects[path.name] = path.stat().st_size
    snapshots = []
    previous = {}
    known = set()
    for path in sorted((root / 'vaults/real-validation/snapshots').glob('*.json')):
        assert path.with_suffix('.sha256').read_text().strip() == digest(path), 'Manifest digest mismatch'
        manifest = json.loads(path.read_text())
        assert manifest['format'] in (1, 2)
        entries = manifest['entries']
        roots = {r['key']: r for r in manifest['roots']}
        current = {}
        hashes = set()
        for entry in entries:
            assert entry['root'] in roots
            key = (entry['root'], entry['path'])
            assert key not in current, 'Duplicate entry'
            current[key] = entry
            if entry['type'] == 'file':
                refs = entry.get('chunks', [entry])
                assert sum(obj['size'] for obj in refs) == entry['size']
                for obj in refs:
                    assert objects[obj['hash']] == obj['size'], 'Object size mismatch'
                    hashes.add(obj['hash'])
                if 'chunks' in entry:
                    h = hashlib.sha256()
                    for obj in refs:
                        with (root / 'objects' / obj['hash'][:2] / obj['hash']).open('rb') as stream:
                            for block in iter(lambda: stream.read(1024 * 1024), b''):
                                h.update(block)
                    assert h.hexdigest() == entry['hash'], 'Reassembled file digest mismatch'
        changed = collections.Counter()
        reused_files = 0
        for key, entry in current.items():
            if entry['type'] != 'file':
                continue
            if key in previous and previous[key].get('hash') == entry['hash']:
                reused_files += 1
            elif previous:
                changed[roots[entry['root']]['kind']] += 1
        snapshots.append({
            'entries': len(entries),
            'entryTypes': dict(collections.Counter(e['type'] for e in entries)),
            'rootKinds': dict(collections.Counter(r['kind'] for r in roots.values())),
            'packages': len(manifest['packages']),
            'lsposedTableRows': {k: len(v) for k, v in manifest['lsposed']['tables'].items()},
            'uniqueObjects': len(hashes),
            'logicalFileBytes': sum(e['size'] for e in entries if e['type'] == 'file'),
            'newUniqueObjects': len(hashes - known),
            'newUniqueBytes': sum(objects[h] for h in hashes - known),
            'unchangedFilesFromPrevious': reused_files,
            'changedOrAddedFilesByKind': dict(changed),
            'removedEntriesFromPrevious': len(previous.keys() - current.keys()),
            'warnings': len(manifest['warnings']),
        })
        known.update(hashes)
        previous = current
    assert len(snapshots) >= 2, 'Two completed snapshots required'
    return {'passed': True, 'transport': 'USB reverse to 127.0.0.1 temporary WebDAV',
            'allObjectDigestsVerified': True, 'allManifestDigestsVerified': True,
            'privatePermissionsVerified': True, 'storedObjects': len(objects),
            'storedBytes': sum(objects.values()), 'unreferencedObjects': len(objects.keys() - known),
            'snapshots': snapshots, 'productionRestorePerformed': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', required=True)
    parser.add_argument('--report', required=True, help='Aggregate-only evidence output')
    args = parser.parse_args()
    result = audit(pathlib.Path(args.root))
    pathlib.Path(args.report).write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False))
