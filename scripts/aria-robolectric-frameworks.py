#!/usr/bin/env python3
"""Prepare exact Robolectric framework jars before the Gradle test process."""
import hashlib
import re
import shutil
import sys
import tempfile
import time
from pathlib import Path
from urllib.request import urlopen
from zipfile import ZipFile

VERSIONS = ('14-robolectric-10818077-i7', '15-robolectric-12650502-i7')
REPOSITORIES = (
    'https://maven-central.storage-download.googleapis.com/maven2',
    'https://repo.maven.apache.org/maven2',
)


def digest(path, algorithm):
    h = hashlib.new(algorithm)
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def valid_jar(path):
    with ZipFile(path) as archive:
        if 'android/app/Application.class' not in archive.namelist():
            raise ValueError('Missing Android Application class')


def cached(path):
    try:
        expected = path.with_suffix('.jar.sha256').read_text().strip()
        if not re.fullmatch(r'[0-9a-f]{64}', expected) or digest(path, 'sha256') != expected:
            return False
        valid_jar(path)
        return True
    except (OSError, ValueError):
        return False
    except Exception:
        return False


def prepare(directory, version):
    name = f'android-all-instrumented-{version}.jar'
    path = directory / name
    if cached(path):
        print(f'Validated cached {name}', flush=True)
        return
    path.unlink(missing_ok=True)
    path.with_suffix('.jar.sha256').unlink(missing_ok=True)
    for attempt in range(2):
        for repo in REPOSITORIES:
            url = f'{repo}/org/robolectric/android-all-instrumented/{version}/{name}'
            try:
                # Maven's published checksum verifies transport integrity. The
                # local SHA-256 records the verified bytes for future cache hits.
                with urlopen(url + '.sha1', timeout=30) as response:
                    expected = response.read(1024).decode('ascii').strip().split()[0].lower()
                if not re.fullmatch(r'[0-9a-f]{40}', expected):
                    raise ValueError('Invalid Maven checksum')
                with tempfile.TemporaryDirectory(dir=directory) as temp:
                    candidate = Path(temp) / name
                    with urlopen(url, timeout=60) as response, candidate.open('wb') as output:
                        shutil.copyfileobj(response, output, 1024 * 1024)
                    if digest(candidate, 'sha1') != expected:
                        raise ValueError('Maven checksum mismatch')
                    valid_jar(candidate)
                    checksum = Path(temp) / 'checksum'
                    checksum.write_text(digest(candidate, 'sha256') + '\n')
                    candidate.replace(path)
                    checksum.replace(path.with_suffix('.jar.sha256'))
                print(f'Prepared {name} from {repo}', flush=True)
                return
            except Exception as exc:
                print(f'Framework download failed from {repo}: {exc}', file=sys.stderr, flush=True)
        if attempt == 0:
            time.sleep(30)
    raise RuntimeError(f'Unable to prepare {name} from either repository')


def main():
    directory = Path(sys.argv[1]).resolve()
    directory.mkdir(parents=True, exist_ok=True)
    for version in VERSIONS:
        prepare(directory, version)


if __name__ == '__main__':
    main()
