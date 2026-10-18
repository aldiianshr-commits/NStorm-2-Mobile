#!/usr/bin/env python3
"""
Assemble the binary assets NStorm 2 Mobile needs and retarget them to the
NStorm package name.

Inputs (all pinned in tools/upstream-assets.lock.json):
  * the upstream Winlator Cmod checkout at a fixed commit (assets committed there),
  * the Winlator Cmod v13.1.1 release APK (rootfs, Proton builds and a few drivers
    that upstream keeps out of git because of their size).

Steps:
  1. verify the APK SHA-256 and every file taken from it,
  2. copy upstream assets that this repo does not track,
  3. rewrite "com.winlator.cmod" -> "com.nstorm2mobile" inside every asset.
     Both strings are 17 bytes long, so ELF binaries, registry files and JSON keep
     every offset intact. Archives (.txz/.tzst/.wcp) are unpacked as tar streams,
     patched member by member (file data, symlink targets, names) and repacked
     with the same compressor. Archives without the string are left untouched.

Usage:
  python3 tools/prepare_assets.py --upstream <checkout> --apk <release.apk> [--out app/src/main]
"""
import argparse
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile

XZ_MAGIC = b"\xfd7zXZ\x00"
ZSTD_MAGIC = b"\x28\xb5\x2f\xfd"


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def log(msg):
    print(msg, flush=True)


def copy_upstream_assets(upstream_assets, out_assets):
    copied = 0
    for root, _dirs, files in os.walk(upstream_assets):
        for name in files:
            src = os.path.join(root, name)
            rel = os.path.relpath(src, upstream_assets)
            dst = os.path.join(out_assets, rel)
            if os.path.exists(dst):
                continue  # tracked in this repo (possibly modified) - keep ours
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(src, dst)
            copied += 1
    log(f"[upstream] copied {copied} untracked asset files")


def extract_from_apk(apk, lock, out_dir):
    want = lock["release_apk"]["files"]
    with zipfile.ZipFile(apk) as z:
        for member, expected in sorted(want.items()):
            dst = os.path.join(out_dir, member)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            h = hashlib.sha256()
            with z.open(member) as src, open(dst, "wb") as f:
                for chunk in iter(lambda: src.read(1 << 20), b""):
                    h.update(chunk)
                    f.write(chunk)
            if h.hexdigest() != expected:
                sys.exit(f"SHA-256 mismatch for {member}: {h.hexdigest()} != {expected}")
            log(f"[apk] {member} ok ({os.path.getsize(dst) / 1e6:.1f} MB)")


def run(cmd, **kw):
    subprocess.run(cmd, check=True, **kw)


def decompress(path, kind, dst_tar):
    tool = ["xz", "-dc", "-T0"] if kind == "xz" else ["zstd", "-dc", "-q"]
    with open(path, "rb") as fin, open(dst_tar, "wb") as fout:
        run(tool, stdin=fin, stdout=fout)


def compress(src_tar, kind, dst):
    tool = ["xz", "-c", "-6", "-T0"] if kind == "xz" else ["zstd", "-c", "-q", "-19", "-T0"]
    with open(src_tar, "rb") as fin, open(dst, "wb") as fout:
        run(tool, stdin=fin, stdout=fout)


def file_contains(path, needle):
    overlap = len(needle) - 1
    tail = b""
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 22), b""):
            if needle in tail + chunk:
                return True
            tail = chunk[-overlap:]
    return False


def patch_tar(src_tar, dst_tar, old, new):
    """Rewrite a tar stream, replacing old->new in names, link targets and file data."""
    old_s, new_s = old.decode(), new.decode()
    stats = {"files": 0, "links": 0, "names": 0, "hits": 0}
    with tarfile.open(src_tar, "r:") as tin, tarfile.open(dst_tar, "w:", format=tarfile.GNU_FORMAT) as tout:
        for m in tin:
            if old_s in m.name:
                m.name = m.name.replace(old_s, new_s)
                stats["names"] += 1
            if m.issym() or m.islnk():
                if old_s in m.linkname:
                    m.linkname = m.linkname.replace(old_s, new_s)
                    stats["links"] += 1
                tout.addfile(m)
            elif m.isreg():
                data = tin.extractfile(m).read()
                n = data.count(old)
                if n:
                    data = data.replace(old, new)
                    stats["files"] += 1
                    stats["hits"] += n
                m.size = len(data)
                tout.addfile(m, io.BytesIO(data))
            else:
                tout.addfile(m)
    return stats


def detect_kind(path):
    with open(path, "rb") as f:
        head = f.read(6)
    if head.startswith(XZ_MAGIC):
        return "xz"
    if head.startswith(ZSTD_MAGIC):
        return "zstd"
    return None


def patch_assets(out_assets, old, new):
    total_archives = 0
    total_plain = 0
    work = tempfile.mkdtemp(prefix="nstorm-assets-")
    try:
        for root, _dirs, files in os.walk(out_assets):
            for name in sorted(files):
                path = os.path.join(root, name)
                rel = os.path.relpath(path, out_assets)
                kind = detect_kind(path) if name.endswith((".txz", ".tzst", ".wcp", ".tar.xz")) else None
                if kind is None:
                    with open(path, "rb") as f:
                        data = f.read()
                    n = data.count(old)
                    if n:
                        with open(path, "wb") as f:
                            f.write(data.replace(old, new))
                        total_plain += 1
                        log(f"[patch] {rel}: {n} occurrence(s)")
                    continue

                raw = os.path.join(work, "in.tar")
                decompress(path, kind, raw)
                if not file_contains(raw, old):
                    os.remove(raw)
                    continue
                patched = os.path.join(work, "out.tar")
                stats = patch_tar(raw, patched, old, new)
                os.remove(raw)
                if file_contains(patched, old):
                    sys.exit(f"{rel}: string still present after patching")
                tmp_out = path + ".new"
                compress(patched, kind, tmp_out)
                os.remove(patched)
                os.replace(tmp_out, path)
                total_archives += 1
                log(f"[patch] {rel} ({kind}): {stats['hits']} hit(s) in {stats['files']} file(s), "
                    f"{stats['links']} link(s), {stats['names']} name(s) -> {os.path.getsize(path) / 1e6:.1f} MB")
    finally:
        shutil.rmtree(work, ignore_errors=True)
    log(f"[patch] done: {total_archives} archive(s), {total_plain} plain file(s) rewritten")


def main():
    ap = argparse.ArgumentParser()
    here = os.path.dirname(os.path.abspath(__file__))
    ap.add_argument("--upstream", required=True, help="checkout of the upstream repo at the pinned commit")
    ap.add_argument("--apk", required=True, help="upstream release APK")
    ap.add_argument("--out", default=os.path.join(here, "..", "app", "src", "main"))
    ap.add_argument("--lock", default=os.path.join(here, "upstream-assets.lock.json"))
    args = ap.parse_args()

    with open(args.lock) as f:
        lock = json.load(f)

    apk_sha = sha256_file(args.apk)
    if apk_sha != lock["release_apk"]["sha256"]:
        sys.exit(f"release APK SHA-256 mismatch: {apk_sha}")
    log(f"[apk] {os.path.basename(args.apk)} sha256 ok")

    upstream_assets = os.path.join(args.upstream, lock["upstream"]["assets_path"])
    out_assets = os.path.join(args.out, "assets")
    if not os.path.isdir(upstream_assets):
        sys.exit(f"missing {upstream_assets}")

    copy_upstream_assets(upstream_assets, out_assets)
    extract_from_apk(args.apk, lock, args.out)

    rename = lock["package_rename"]
    old, new = rename["from"].encode(), rename["to"].encode()
    if len(old) != len(new):
        sys.exit("package names must have the same length for in-place binary patching")
    patch_assets(out_assets, old, new)


if __name__ == "__main__":
    main()
