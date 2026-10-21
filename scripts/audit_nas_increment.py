#!/usr/bin/env python3
"""Compare committed NAS manifests; save aggregates, never application configurations."""
import argparse
import base64
import hashlib
import http.client
import json
import re
import ssl
import urllib.parse

parser = argparse.ArgumentParser()
parser.add_argument('--config', required=True)
parser.add_argument('--vault', required=True)
parser.add_argument('--previous', required=True)
parser.add_argument('--snapshot', required=True)
parser.add_argument('--output', required=True)
parser.add_argument('--fixture-version', type=int, choices=(1, 2))
parser.add_argument('--expect-no-fixture', action='store_true')
parser.add_argument('--uploaded-objects', type=int, required=True)
parser.add_argument('--uploaded-bytes', type=int, required=True)
args = parser.parse_args()
assert re.fullmatch(r'[A-Za-z0-9_-]{1,80}', args.vault)
for value in (args.previous, args.snapshot):
    assert re.fullmatch(r'[0-9]+-[a-f0-9]{8}', value)
with open(args.config) as f:
    config = json.load(f)
endpoint = urllib.parse.urlsplit(urllib.parse.quote(config['url'].rstrip('/') + '/', safe=':/%'))
assert endpoint.scheme == 'https' and not endpoint.username and not endpoint.query and not endpoint.fragment
auth = 'Basic ' + base64.b64encode((config['username'] + ':' + config['password']).encode()).decode()
client = http.client.HTTPSConnection(endpoint.hostname, endpoint.port, timeout=90, context=ssl.create_default_context())


def get(path, limit):
    client.request('GET', endpoint.path + path, headers={'Authorization': auth, 'Accept-Encoding': 'identity'})
    with client.getresponse() as response:
        assert response.status == 200, 'Unexpected NAS response; redirects are not followed'
        body = response.read(limit + 1)
        assert len(body) <= limit
        return body


def read(snapshot):
    prefix = 'vaults/' + args.vault + '/snapshots/' + snapshot
    raw = get(prefix + '.json', 64 * 1024 * 1024)
    assert hashlib.sha256(raw).hexdigest() == get(prefix + '.sha256', 100).decode().strip()
    result = json.loads(raw)
    assert result['id'] == snapshot and result['vault'] == args.vault and result['format'] in (1, 2)
    return result


def inventory(manifest):
    files, objects = {}, {}
    for entry in manifest['entries']:
        if entry['type'] != 'file':
            continue
        files[(entry['root'], entry['path'])] = entry['hash']
        refs = entry.get('chunks', [entry])
        assert sum(obj['size'] for obj in refs) == entry['size']
        for obj in refs:
            assert re.fullmatch(r'[a-f0-9]{64}', obj['hash'])
            old = objects.setdefault(obj['hash'], obj['size'])
            assert old == obj['size']
    return files, objects


def verify_object(digest, size, assembled=None):
    headers = {'Authorization': auth, 'Accept-Encoding': 'identity'}
    if size > 0:
        headers['Range'] = 'bytes=0-' + str(size - 1)
    client.request('GET', endpoint.path + 'objects/' + digest[:2] + '/' + digest, headers=headers)
    md, total = hashlib.sha256(), 0
    with client.getresponse() as response:
        assert response.status in (200, 206)
        if response.status == 206:
            assert size > 0 and response.headers.get('Content-Range') == 'bytes 0-%d/%d' % (size - 1, size)
        while True:
            block = response.read(262144)
            if not block:
                break
            total += len(block)
            assert total <= size
            md.update(block)
            if assembled is not None:
                assembled.update(block)
    assert total == size and md.hexdigest() == digest, 'New object verification failed'


previous, current = read(args.previous), read(args.snapshot)
old_files, old_objects = inventory(previous)
files, objects = inventory(current)
new_hashes = objects.keys() - old_objects.keys()
for digest in sorted(new_hashes):
    verify_object(digest, objects[digest])
assembled_count = 0
for entry in current['entries']:
    if 'chunks' not in entry or old_files.get((entry['root'], entry['path'])) == entry['hash']:
        continue
    md = hashlib.sha256()
    for obj in entry['chunks']:
        verify_object(obj['hash'], obj['size'], md)
    assert md.hexdigest() == entry['hash'], 'Changed chunked file reassembly mismatch'
    assembled_count += 1
fixture = next((r for r in current['roots'] if r['kind'] == 'module' and
                r['detail']['id'] == 'nasbackup_validation_fixture'), None)
if args.fixture_version is not None:
    assert fixture and fixture['detail']['version'] == str(args.fixture_version)
    expected = hashlib.sha256(('nasbackup-validation-payload-v%d\n' % args.fixture_version).encode()).hexdigest()
    assert files[(fixture['key'], 'payload.txt')] == expected
if args.expect_no_fixture:
    assert fixture is None
result = {
    'previousSnapshot': args.previous, 'snapshot': args.snapshot, 'manifestHashesVerified': True,
    'ordinaryFiles': len(files), 'unchangedFiles': sum(old_files.get(k) == v for k, v in files.items()),
    'changedOrAddedFiles': sum(old_files.get(k) != v for k, v in files.items()),
    'removedFiles': len(old_files.keys() - files.keys()),
    'reusedContentObjects': len(objects.keys() & old_objects.keys()),
    'newUniqueObjects': len(new_hashes), 'newUniqueBytes': sum(objects[h] for h in new_hashes),
    'allNewObjectsIndependentlyVerified': True, 'changedChunkedFilesReassembledAndVerified': assembled_count,
    'actualUploadedObjects': args.uploaded_objects, 'actualUploadedBytes': args.uploaded_bytes,
    'uploadsWithinNewContent': args.uploaded_objects <= len(new_hashes) and
                              args.uploaded_bytes <= sum(objects[h] for h in new_hashes),
    'fixtureVersion': fixture['detail']['version'] if fixture else None,
    'productionRestorePerformed': False
}
with open(args.output, 'w') as f:
    json.dump(result, f, ensure_ascii=False, indent=2)
print(json.dumps(result, ensure_ascii=False))
