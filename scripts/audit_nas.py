#!/usr/bin/env python3
"""Read-only NAS manifest/object audit; credentials come from a private config file."""
import argparse
import base64
import concurrent.futures
import hashlib
import http.client
import json
import re
import ssl
import threading
import time
import urllib.parse


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--config', required=True)
    p.add_argument('--vault', required=True)
    p.add_argument('--snapshot', required=True)
    p.add_argument('--workers', type=int, default=6)
    p.add_argument('--output', required=True)
    args = p.parse_args()
    assert re.fullmatch(r'[A-Za-z0-9_-]{1,80}', args.vault)
    assert re.fullmatch(r'[0-9]+-[a-f0-9]{8}', args.snapshot)
    with open(args.config) as f:
        config = json.load(f)
    base = urllib.parse.quote(config['url'].rstrip('/') + '/', safe=':/%')
    endpoint = urllib.parse.urlsplit(base)
    assert endpoint.scheme == 'https' and endpoint.hostname and not endpoint.username
    assert not endpoint.query and not endpoint.fragment
    auth = 'Basic ' + base64.b64encode((config['username'] + ':' + config['password']).encode()).decode()
    connections = threading.local()

    def response(path, size=None):
        headers = {'Authorization': auth, 'Accept-Encoding': 'identity'}
        if size is not None and size > 0:
            headers['Range'] = 'bytes=0-' + str(size - 1)
        if not hasattr(connections, 'client'):
            connections.client = http.client.HTTPSConnection(endpoint.hostname, endpoint.port, timeout=90,
                                                            context=ssl.create_default_context())
        connections.client.request('GET', endpoint.path + path, headers=headers)
        result = connections.client.getresponse()
        assert result.status in (200, 206), 'Unexpected NAS status %d; redirects are not followed' % result.status
        if result.status == 206:
            assert size is not None and size > 0
            assert result.headers.get('Content-Range') == 'bytes 0-%d/%d' % (size - 1, size)
        return result

    prefix = 'vaults/' + args.vault + '/snapshots/' + args.snapshot
    with response(prefix + '.json') as r:
        raw = r.read(64 * 1024 * 1024 + 1)
    assert len(raw) <= 64 * 1024 * 1024
    with response(prefix + '.sha256') as r:
        expected = r.read(100).decode().strip()
    assert hashlib.sha256(raw).hexdigest() == expected, 'Manifest hash mismatch'
    manifest = json.loads(raw)
    assert manifest['id'] == args.snapshot and manifest['vault'] == args.vault
    assert manifest['format'] in (1, 2)
    objects = {}
    chunked_files = {}
    for entry in manifest['entries']:
        if entry['type'] == 'file':
            assert re.fullmatch(r'[a-f0-9]{64}', entry['hash']) and entry['size'] >= 0
            if 'chunks' in entry:
                assert manifest['format'] == 2 and 0 < len(entry['chunks']) <= 4096
                assert sum(c['size'] for c in entry['chunks']) == entry['size']
                chunked_files[entry['hash']] = entry
            for obj in entry.get('chunks', [entry]):
                assert re.fullmatch(r'[a-f0-9]{64}', obj['hash']) and obj['size'] >= 0
                if 'chunks' in entry:
                    assert 0 < obj['size'] <= 8 * 1024 * 1024
                previous = objects.setdefault(obj['hash'], obj['size'])
                assert previous == obj['size']

    def verify(item):
        digest, size = item
        md, total = hashlib.sha256(), 0
        with response('objects/' + digest[:2] + '/' + digest, size) as r:
            while True:
                block = r.read(262144)
                if not block:
                    break
                total += len(block)
                assert total <= size, 'Object size exceeded'
                md.update(block)
        assert total == size and md.hexdigest() == digest, 'Object integrity mismatch'
        return total

    started = time.time()
    total = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        for count, size in enumerate(pool.map(verify, objects.items()), 1):
            total += size
            if count % 200 == 0:
                print(json.dumps({'verifiedObjects': count, 'totalObjects': len(objects), 'verifiedBytes': total}), flush=True)
    # Re-read chunks in file order and independently verify the original full-file hash.
    def verify_assembled(entry):
        digest, size = hashlib.sha256(), 0
        for chunk in entry['chunks']:
            h = chunk['hash']
            with response('objects/' + h[:2] + '/' + h, chunk['size']) as r:
                while True:
                    block = r.read(262144)
                    if not block:
                        break
                    size += len(block)
                    assert size <= entry['size']
                    digest.update(block)
        assert size == entry['size'] and digest.hexdigest() == entry['hash'], 'Reassembled file mismatch'
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        for count, _ in enumerate(pool.map(verify_assembled, chunked_files.values()), 1):
            print(json.dumps({'verifiedReassembledFiles': count, 'totalChunkedFiles': len(chunked_files)}), flush=True)
    result = {'snapshot': args.snapshot, 'vault': args.vault, 'manifestSha256': expected,
              'entries': len(manifest['entries']), 'uniqueObjects': len(objects),
              'verifiedBytes': total, 'allObjectsVerified': True,
              'chunkedFilesReassembledAndVerified': len(chunked_files),
              'roots': len(manifest['roots']), 'packageUserPairs': len(manifest['packages']),
              'lsposedCounts': manifest['lsposed'].get('counts'),
              'seconds': round(time.time() - started, 1), 'restorePerformed': False}
    with open(args.output, 'w') as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(json.dumps(result, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
