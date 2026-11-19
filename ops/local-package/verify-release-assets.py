"""Verify the exact files to attach to a draft release after both package jobs pass."""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile


def verify(directory, commit, runtime):
    source = runtime["source"]
    packages = {"windows": ["vortex-local-windows-x64.zip", source["name"]],
                "universal": ["vortex-local-alpha.zip"]}
    for platform, names in packages.items():
        folder = directory / platform
        expected = set(names) | {name + ".sha256" for name in names}
        if {p.name for p in folder.iterdir()} != expected:
            raise ValueError(f"{folder} must contain exactly {sorted(expected)}")
        for name in names:
            path = folder / name
            with path.open("rb") as binary:
                checksum = hashlib.file_digest(binary, "sha256").hexdigest()
            if (folder / (name + ".sha256")).read_text(encoding="ascii").split() != [checksum, name]:
                raise ValueError(f"File checksum or name mismatch: {name}")
            if name == source["name"]:
                if checksum != source["sha256"]:
                    raise ValueError("Wrong Temurin source for this release")
            else:
                with ZipFile(path) as archive:
                    manifest = json.loads(archive.read("manifest.json"))
                if manifest["base_commit"] != commit:
                    raise ValueError(f"{name} did not come from the tested commit {commit}")
                if platform == "windows" and manifest["runtime"] != runtime:
                    raise ValueError(f"Unexpected runtime in {name}")
            print(f"{name}: {checksum}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="Downloaded Actions artifacts in windows/ and universal/")
    parser.add_argument("commit", help="Commit tested by the reusable workflow")
    args = parser.parse_args()
    lock = Path(__file__).resolve().parents[1] / "temurin-windows-x64.json"
    verify(args.directory, args.commit, json.loads(lock.read_text(encoding="utf-8")))
