#!/usr/bin/env python3
"""Build an inert, clearly named module for explicit real-device scheduling tests."""
import argparse
import zipfile
from build import BUILD, entry

parser = argparse.ArgumentParser()
parser.add_argument('--version', type=int, choices=(1, 2), required=True)
args = parser.parse_args()
BUILD.mkdir(exist_ok=True)
target = BUILD / ('nasbackup-validation-fixture-v%d.zip' % args.version)
prop = ('id=nasbackup_validation_fixture\n'
        'name=NAS Backup Temporary Validation Fixture\n'
        'version=%d\nversionCode=%d\n'
        'author=NAS Backup Test\n'
        'description=Temporary inert fixture, contains no executable hooks or system files.\n') % (args.version, args.version)
with zipfile.ZipFile(target, 'w') as archive:
    entry(archive, 'module.prop', prop.encode())
    entry(archive, 'skip_mount', b'')
    entry(archive, 'payload.txt', ('nasbackup-validation-payload-v%d\n' % args.version).encode())
print(target)
