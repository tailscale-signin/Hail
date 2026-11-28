#!/usr/bin/env python3
"""Import an explicit UAD-ng commit. APKs use this offline snapshot, never live network ratings."""
import argparse
import collections
import gzip
import hashlib
import json
import pathlib
import re
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument("commit", help="Reviewed, full 40-character upstream commit SHA")
parser.add_argument("--source", type=pathlib.Path, help="Local copy of that commit's uad_lists.json")
args = parser.parse_args()
if not re.fullmatch(r"[0-9a-f]{40}", args.commit):
    parser.error("A full upstream commit SHA is required")
url = ("https://raw.githubusercontent.com/Universal-Debloater-Alliance/"
       f"universal-android-debloater-next-generation/{args.commit}/resources/assets/uad_lists.json")
raw = args.source.read_bytes() if args.source else urllib.request.urlopen(url, timeout=30).read()
data = json.loads(raw)
levels = {"Recommended", "Advanced", "Expert", "Unsafe"}
assert len(data) > 1000, "Incomplete community snapshot"
assert all(isinstance(name, str) and isinstance(entry.get("description"), str)
           and entry.get("removal") in levels for name, entry in data.items())
folder = pathlib.Path(__file__).resolve().parents[1] / "app/src/main/assets/app-risk"
folder.mkdir(parents=True, exist_ok=True)
compressed = gzip.compress(raw, mtime=0)
(folder / "uad_lists.json.gz").write_bytes(compressed)
metadata = {
    "source": url,
    "commit": args.commit,
    "sha256": hashlib.sha256(raw).hexdigest(),
    "packages": len(data),
    "categories": dict(sorted(collections.Counter(entry["removal"] for entry in data.values()).items())),
    "license": "GPL-3.0",
    "transform": "Unmodified upstream JSON, compressed with gzip; no ratings inferred or removed.",
}
(folder / "source.json").write_text(json.dumps(metadata, indent=2) + "\n")
print(f"Stored {len(data)} exact package ratings ({len(compressed)} compressed bytes).")
