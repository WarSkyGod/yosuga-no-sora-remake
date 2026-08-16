#!/usr/bin/env python3
"""Package the game data/ tree into downloadable zip archives.

The OpenHarmony HAP no longer bundles the multi-GiB game data; instead the
CI workflow publishes the data as one or more zip assets (each below the
2 GiB GitHub limit) plus a JSON manifest that the in-game downloader reads.

Files are grouped by the packs in content-packs.json so the zips stay
reasonably small while keeping the data/ relative layout intact:

    data/<relative path>

Extraction of every asset into one directory therefore reproduces the
original data/ tree.
"""

from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from typing import Any, Dict, Iterable, List, Tuple


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True, help="Content root (the data/ directory)")
    parser.add_argument("--config", type=Path, required=True, help="content-packs.json")
    parser.add_argument("--tag", required=True, help="Release tag, e.g. v0.1.0-test.1")
    parser.add_argument("--out", type=Path, required=True, help="Directory for the zip assets")
    parser.add_argument("--max-size", type=int, default=1800, help="Max raw size per zip in MiB")
    parser.add_argument("--archiver", default="7z", choices=["7z", "zip"], help="zip tool to use")
    return parser.parse_args()


def read_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def iter_content_files(root: Path) -> Iterable[str]:
    for directory, directory_names, file_names in os.walk(str(root), followlinks=False):
        directory_names.sort()
        file_names.sort()
        for file_name in file_names:
            relative = os.path.relpath(os.path.join(directory, file_name), str(root))
            yield relative.replace(os.sep, "/")


def select_pack(relative: str, packs: List[Dict[str, Any]], default_pack: str, exclude: List[str]) -> str:
    if any(fnmatch.fnmatchcase(relative, pattern) for pattern in exclude):
        return ""
    for pack in packs:
        if any(fnmatch.fnmatchcase(relative, pattern) for pattern in pack.get("include", [])):
            return str(pack["id"])
    return str(default_pack)


def main() -> int:
    args = parse_args()
    root: Path = args.root
    if not (root / "startup.tjs").is_file():
        print("error: %s/startup.tjs is missing" % root, file=sys.stderr)
        return 1

    config = read_json(args.config)
    packs = config.get("packs", [])
    exclude = config.get("exclude", [])
    default_pack = config.get("defaultPack", "misc")

    by_pack: Dict[str, List[str]] = {}
    total = 0
    for relative in iter_content_files(root):
        pack_id = select_pack(relative, packs, default_pack, exclude)
        if not pack_id:
            continue
        path = root / relative
        try:
            size = path.stat().st_size
        except OSError:
            continue
        by_pack.setdefault(pack_id, []).append(relative)
        total += size

    print("total data size: %d bytes across %d packs" % (total, len(by_pack)))

    # Order: explicit packs as configured, default (misc) last.
    ordered: List[Tuple[str, List[str]]] = []
    for pack in packs:
        files = by_pack.pop(pack["id"], None)
        if files:
            ordered.append((pack["id"], files))
    misc_files = by_pack.pop(default_pack, None)
    if misc_files:
        ordered.append((default_pack, misc_files))
    if by_pack:
        print("warning: packs not emitted: %s" % sorted(by_pack), file=sys.stderr)

    # Batch packs into zip assets below the size limit.
    max_bytes = args.max_size * 1024 * 1024
    batches: List[List[Tuple[str, List[str]]]] = []
    current: List[Tuple[str, List[str]]] = []
    current_size = 0
    for pack_id, files in ordered:
        pack_size = sum((root / f).stat().st_size for f in files)
        if current and current_size + pack_size > max_bytes:
            batches.append(current)
            current = []
            current_size = 0
        current.append((pack_id, files))
        current_size += pack_size
    if current:
        batches.append(current)

    args.out.mkdir(parents=True, exist_ok=True)
    assets = []
    root_parent = str(root.parent)
    for index, batch in enumerate(batches, start=1):
        name = "Yosuga-no-Sora-HD-Remake-OpenHarmony-data-%02d-%s.zip" % (index, args.tag)
        archive = args.out / name
        # Pass the file list through a list file: thousands of absolute
        # paths exceed the OS command-line length limit.
        list_path = args.out / (name + ".list")
        with list_path.open("w", encoding="utf-8") as list_handle:
            for pack_id, files in batch:
                for relative in files:
                    list_handle.write(os.path.join(root_parent, relative) + "\n")
        with open(os.devnull, "w") as devnull:
            if args.archiver == "7z":
                cmd = ["7z", "a", "-tzip", "-mx=9", "-bso0", "-bsp0", str(archive), "@" + str(list_path)]
                result = subprocess.run(cmd, stdout=devnull, stderr=devnull)
            else:
                cmd = ["zip", "-9", "-q", "-y", str(archive), "-@"]
                with list_path.open("r", encoding="utf-8") as list_handle:
                    result = subprocess.run(cmd, stdin=list_handle, stdout=devnull, stderr=devnull)
        try:
            list_path.unlink()
        except OSError:
            pass
        if result.returncode != 0:
            print("error: archiving %s failed with exit %d" % (name, result.returncode), file=sys.stderr)
            return 1

        size = archive.stat().st_size
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        assets.append({
            "name": name,
            "size": size,
            "sha256": digest,
            "packs": [pack_id for pack_id, _ in batch],
            "fileCount": sum(len(files) for _, files in batch),
        })
        print("asset %s: %d bytes, packs=%s" % (name, size, ",".join(assets[-1]["packs"])))

    manifest = {
        "schemaVersion": 1,
        "releaseTag": args.tag,
        "baseUrl": "https://github.com/WarSkyGod/yosuga-no-sora-remake/releases/download/%s" % args.tag,
        "assets": assets,
    }
    manifest_path = args.out / "data-assets.json"
    with manifest_path.open("w", encoding="utf-8", newline="\n") as handle:
        json.dump(manifest, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("manifest: %s" % manifest_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
