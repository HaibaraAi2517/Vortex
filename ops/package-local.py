"""Build the local trial ZIP. Include the model and SDK; Java 21 is supplied separately."""
import hashlib
import json
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import xml.etree.ElementTree as ET
import zipfile


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def main():
    root = Path(__file__).resolve().parent.parent
    version = ET.parse(root / "pom.xml").getroot().find("{http://maven.apache.org/POM/4.0.0}version").text
    jar = root / f"vortex-app/target/vortex-app-{version}-exec.jar"
    model_manifest = json.loads((root / "models/manifest.json").read_text(encoding="utf-8"))
    files = {"vortex-app.jar": jar, "LICENSE": root / "LICENSE", "THIRD_PARTY_NOTICES.md": root / "THIRD_PARTY_NOTICES.md",
             "demo.py": root / "examples/personal-memory/demo.py",
             "agent.py": root / "examples/personal-memory/agent.py",
             "automatic-memory.md": root / "docs/automatic-agent-memory.md",
             "docs/local-memory.md": root / "docs/local-memory.md",
             "docs/local-memory-verification.md": root / "docs/local-memory-verification.md",
             "docs/automatic-agent-memory.md": root / "docs/automatic-agent-memory.md",
             "docs/automatic-agent-memory-verification.md": root / "docs/automatic-agent-memory-verification.md",
             "ops/run-codex-memory.py": root / "ops/run-codex-memory.py",
             "examples/codex-memory/AGENTS.md": root / "examples/codex-memory/AGENTS.md",
             "docs/codex-memory.md": root / "docs/codex-memory.md",
             "docs/codex-memory-verification.md": root / "docs/codex-memory-verification.md"}
    for item in model_manifest["bge-small-zh"]:
        model = root / "models/bge-small-zh" / item["file"]
        if not model.is_file() or digest(model) != item["sha256"]:
            raise ValueError("Run python ops/download-model.py before packaging")
        files[f"models/bge-small-zh/{item['file']}"] = model
    wheels = sorted((root / "sdk/python/dist").glob("*.whl"))
    if len(wheels) != 1:
        raise ValueError("Build exactly one current wheel with python -m build sdk/python before packaging")
    wheel = wheels[0]
    # A wheel from an earlier checkout may have the same package version.
    with zipfile.ZipFile(wheel) as built:
        sdk = root / "sdk/python/src"
        expected = {p.relative_to(sdk).as_posix(): p for p in (sdk / "vortex_memory").rglob("*.py")}
        packaged = {name for name in built.namelist() if name.startswith("vortex_memory/") and name.endswith(".py")}
        if packaged != expected.keys() or any(built.read(name).splitlines() != p.read_bytes().splitlines()
                                              for name, p in expected.items()):
            raise ValueError("Wheel differs from current SDK sources; rebuild sdk/python before packaging")
    files["python/" + wheel.name] = wheel
    launchers = root / "ops/local-package"
    for name in ("Start Vortex.cmd", "start.ps1"):
        files[name] = launchers / name
    evidence = root / "target/local-memory-smoke.json"
    if not evidence.is_file():
        raise ValueError("Run ops/verify-local-memory.py --mcp before packaging")
    smoke = json.loads(evidence.read_text(encoding="utf-8"))
    if smoke.get("status") != "passed" or smoke.get("jar_sha256") != digest(jar):
        raise ValueError("The passing smoke report must match the exact jar being packaged")
    if not smoke.get("mcp_checked") or smoke.get("distractors", 0) < 256:
        raise ValueError("Run the full ops/verify-local-memory.py --mcp before packaging")
    # The development smoke report may contain absolute SDK paths. Keep only the public evidence.
    public_evidence = {k: smoke[k] for k in ("checks", "distractors", "embedding", "external_llm_used",
                                              "status", "elapsed_seconds", "jar_sha256", "mcp_checked")}
    generated = {
        "start.sh": (launchers / "start.sh").read_text(encoding="utf-8").encode("utf-8"),
        "verification.json": json.dumps(public_evidence, ensure_ascii=False, indent=2).encode("utf-8"),
        "README.txt": (launchers / "README.txt").read_bytes().replace(b"@WHEEL@", wheel.name.encode("ascii")),
    }
    manifest = {"distribution": "Vortex local memory alpha", "base_pom_version": version,
                "java": "21", "files": {name: digest(path) for name, path in files.items()}}
    manifest["files"].update({name: hashlib.sha256(contents).hexdigest() for name, contents in generated.items()})
    result = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, capture_output=True, text=True)
    manifest["base_commit"] = result.stdout.strip() if result.returncode == 0 else "unknown"
    manifest["note"] = "The Git revision identifies the base checkout. The per-file SHA-256 values identify the packaged contents."
    output = root / "target/vortex-local-alpha.zip"
    with TemporaryDirectory(prefix=".pack-local-", dir=output.parent) as temp:
        archive = Path(temp) / output.name
        with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as bundle:
            for name, path in files.items():
                bundle.write(path, name)
            for name, contents in generated.items():
                bundle.writestr(name, contents)
            bundle.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        checksum = archive.with_suffix(".zip.sha256")
        checksum.write_text(f"{digest(archive)}  {archive.name}\n", encoding="ascii")
        archive.replace(output)
        checksum.replace(output.with_suffix(".zip.sha256"))
    print(output)
    print(f"{output.stat().st_size / 1024**2:.1f} MiB; model and wheel included")


if __name__ == "__main__":
    main()
