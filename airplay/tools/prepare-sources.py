#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Verify and unpack pinned source archives, then apply upstream/local patches.
No network or binary downloads. Run once before invoking CMake directly.
"""
from pathlib import Path
import hashlib, json, os, shutil, subprocess, tarfile, tempfile

root = Path(__file__).resolve().parents[1]
cpp = root / 'src/main/cpp'
manifest = json.loads((root / 'vendor/sources.json').read_text())
for name in ['UxPlay', 'ffmpeg', 'libplist', 'openssl-cmake', 'oboe']:
    item = manifest[name]
    archive = root / 'vendor/sources' / item['archive']
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == item['sha256'], name
    destination = cpp / 'third_party' / name
    marker = destination / '.source-sha256'
    stamp = item['sha256'] + hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    if name == 'UxPlay':
        stamp += hashlib.sha256(b''.join(p.read_bytes() for p in sorted((cpp/'patches/UxPlay').glob('*.patch')))).hexdigest()
    if marker.exists() and marker.read_text() == stamp:
        continue
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True)
    with tarfile.open(archive) as tar:
        if name == 'oboe':
            prefix = tar.getnames()[0].split('/')[0] + '/'
            members = []
            for member in tar.getmembers():
                if not member.name.startswith(prefix):
                    continue
                member.name = member.name[len(prefix):]
                if member.name:
                    members.append(member)
            tar.extractall(destination, members=members, filter='data')
        else:
            tar.extractall(destination, filter='data')
    if name == 'UxPlay':
        for patch in sorted((cpp/'patches/UxPlay').glob('*.patch')):
            # Run outside the host checkout so git apply cannot silently skip
            # ignored vendored paths when this source tree is nested in a repo.
            with tempfile.TemporaryDirectory(prefix='puppy-patch-') as outside:
                subprocess.run(['git', 'apply', '--unsafe-paths', '--unidiff-zero', '--directory', str(destination), str(patch)],
                    cwd=outside, env={**os.environ, 'GIT_CEILING_DIRECTORIES': outside}, check=True)
    if name == 'openssl-cmake':
        # Use the verified, bundled official source instead of an unpinned mirror download.
        build = destination / 'cmake/BuildOpenSSL.cmake'
        text = build.read_text().replace('URL https://mirror.viaduck.org/openssl/openssl-${OPENSSL_BUILD_VERSION}.tar.gz',
            'URL ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../vendor/sources/openssl-${OPENSSL_BUILD_VERSION}.tar.gz')
        build.write_text(text)
    marker.write_text(stamp)
print('Reference sources verified and prepared')
