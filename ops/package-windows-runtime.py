"""Package the checked local bundle with a pinned Temurin JRE and its source sidecar."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
from tempfile import TemporaryDirectory
from urllib.request import urlopen
from uuid import uuid4
from zipfile import ZIP_DEFLATED, ZipFile

ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT / "ops/temurin-windows-x64.json").read_text(encoding="utf-8"))
TARGET = ROOT / "target"


def digest(path):
    checksum = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            checksum.update(chunk)
    return checksum.hexdigest()


def fetch(cache, asset):
    path = cache / asset["name"]
    if path.is_file() and digest(path) == asset["sha256"]:
        return path
    tmp = path.with_name(path.name + ".part-" + uuid4().hex)
    try:
        print(f"Downloading {asset['name']}", flush=True)
        with urlopen(LOCK["download_base"] + asset["name"], timeout=90) as source, tmp.open("xb") as dest:
            shutil.copyfileobj(source, dest, 1024 * 1024)
        if digest(tmp) != asset["sha256"]:
            raise ValueError(f"SHA-256 mismatch for {asset['name']}")
        tmp.replace(path)
    finally:
        tmp.unlink(missing_ok=True)
    return path


def copy_entry(src, file, dest, name):
    checksum = hashlib.sha256()
    with src.open(file) as reader, dest.open(name, "w") as writer:
        while chunk := reader.read(1024 * 1024):
            checksum.update(chunk)
            writer.write(chunk)
    return checksum.hexdigest()


def build(base_path, jre_path, out_path):
    with ZipFile(base_path) as base, ZipFile(jre_path) as jre, ZipFile(out_path, "w", ZIP_DEFLATED, compresslevel=6) as out:
        manifest = json.loads(base.read("manifest.json"))
        hashes = manifest["files"]
        base_files = [item.filename for item in base.infolist() if not item.is_dir()]
        if sorted(base_files) != sorted([*hashes, "manifest.json"]) or "Start Vortex.cmd" not in hashes:
            raise ValueError("Rebuild the base archive with ops/package-local.py: expected one hash per packaged file and a Windows launcher")
        for name, expected in hashes.items():
            if name.startswith("runtime/") or copy_entry(base, name, out, name) != expected:
                raise ValueError(f"Unexpected base archive content: {name}")
        prefix = LOCK["archive_root"]
        for entry in jre.infolist():
            if not entry.is_dir():
                if not entry.filename.startswith(prefix):
                    raise ValueError(f"Unexpected entry in JRE: {entry.filename}")
                name = "runtime/" + entry.filename[len(prefix):]
                hashes[name] = copy_entry(jre, entry, out, name)
        required = {"runtime/bin/java.exe", "runtime/release", "runtime/NOTICE", "runtime/legal/java.base/LICENSE"}
        if missing := required.difference(hashes):
            raise ValueError(f"Incomplete runtime: missing {sorted(missing)}")
        manifest["platform"] = "windows-x64"
        manifest["runtime"] = LOCK
        source = LOCK["source"]["name"]
        notices = (f"The complete Eclipse Temurin {LOCK['version']} Windows x64 HotSpot JRE is included in runtime/.\n"
                   "See runtime/NOTICE, runtime/legal/ and runtime/release for licenses and build metadata.\n"
                   f"Corresponding source is distributed alongside this ZIP as {source}.\n"
                   f"Upstream source: {LOCK['download_base']}{source}\n"
                   f"Source SHA-256: {LOCK['source']['sha256']}\n"
                   f"Build scripts: {LOCK['build_source']}\n").encode("utf-8")
        out.writestr("runtime-source.txt", notices)
        hashes["runtime-source.txt"] = hashlib.sha256(notices).hexdigest()
        out.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))


def checksum_file(path):
    return path.with_name(path.name + ".sha256")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, default=TARGET / "vortex-local-alpha.zip")
    parser.add_argument("--cache", type=Path, default=TARGET / "runtime-cache")
    parser.add_argument("--output", type=Path, default=TARGET / "vortex-local-windows-x64.zip")
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.base.resolve() == args.output.resolve():
        parser.error("--output must differ from --base")
    binary = fetch(args.cache, LOCK["binary"])
    source = fetch(args.cache, LOCK["source"])
    # Finish constructing every deliverable before replacing earlier outputs.
    with TemporaryDirectory(prefix=".pack-windows-", dir=args.output.parent) as directory:
        staging = Path(directory)
        package = staging / args.output.name
        build(args.base, binary, package)
        checksum_file(package).write_text(f"{digest(package)}  {package.name}\n", encoding="ascii")
        shutil.copyfile(source, staging / source.name)
        checksum_file(staging / source.name).write_text(f"{LOCK['source']['sha256']}  {source.name}\n", encoding="ascii")
        for file in (staging / source.name, checksum_file(staging / source.name), package, checksum_file(package)):
            dest = args.output.parent / file.name
            file.replace(dest)
            print(dest)


if __name__ == "__main__":
    main()
