"""Fetch pinned upstream model artifacts, verify SHA-256, and publish each file atomically."""
import argparse
import hashlib
import json
import os
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import urlopen
from uuid import uuid4


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(model, output, base_url, manifest):
    if urlsplit(base_url).scheme != "https":
        raise ValueError("Model origin/mirror must use HTTPS")
    for artifact in manifest[model]:
        destination = output / model / artifact["file"]
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists():
            if sha256(destination) != artifact["sha256"]:
                raise ValueError(f"Checksum mismatch for existing {destination}; move it aside before downloading the pinned version")
            print("Verified", destination)
            continue
        temporary = destination.with_suffix(destination.suffix + ".part-" + uuid4().hex)
        try:
            print("Downloading", model, artifact["file"], flush=True)
            with urlopen(base_url.rstrip("/") + "/" + artifact["source"], timeout=60) as source, temporary.open("wb") as target:
                while chunk := source.read(1024 * 1024):
                    target.write(chunk)
            if sha256(temporary) != artifact["sha256"]:
                raise ValueError(f"Downloaded checksum mismatch for {destination}")
            temporary.replace(destination)
        finally:
            temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    root = Path(__file__).resolve().parent.parent
    manifest = json.loads((root / "models/manifest.json").read_text(encoding="utf-8"))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", choices=manifest, default="bge-small-zh")
    parser.add_argument("--output", type=Path, default=root / "models")
    parser.add_argument("--base-url", default=os.environ.get("VORTEX_MODEL_BASE_URL", "https://huggingface.co"))
    args = parser.parse_args()
    download(args.model, args.output, args.base_url, manifest)
