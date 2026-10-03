#!/usr/bin/env python3
"""Fetch pinned runtime dependencies; verify both new and cached downloads."""
from pathlib import Path
import hashlib, json, urllib.request, sys
root = Path(__file__).resolve().parent
host = '--host' in sys.argv
cache = root / 'build' / ('host-deps' if host else 'deps')
cache.mkdir(parents=True, exist_ok=True)
for item in json.loads((root / ('host-dependencies.json' if host else 'dependencies.json')).read_text()):
    target = cache / item['file']
    data = target.read_bytes() if target.exists() else urllib.request.urlopen(item['url'], timeout=30).read()
    if hashlib.sha256(data).hexdigest() != item['sha256']:
        raise SystemExit('Dependency checksum mismatch: ' + item['file'])
    if not target.exists():
        target.write_bytes(data)
