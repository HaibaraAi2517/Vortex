"""Build a self-contained trial archive (Java 21 required; no Maven or model download at runtime)."""
import hashlib
import json
from pathlib import Path
import subprocess
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
    files["python/" + wheels[0].name] = wheels[0]
    evidence = root / "target/local-memory-smoke.json"
    if not evidence.is_file():
        raise ValueError("Run ops/verify-local-memory.py --mcp before packaging")
    smoke = json.loads(evidence.read_text(encoding="utf-8"))
    if smoke.get("status") != "passed" or smoke.get("jar_sha256") != digest(jar):
        raise ValueError("The passing smoke report must match the exact jar being packaged")
    files["verification.json"] = evidence
    manifest = {"distribution": "personal-memory working-tree alpha", "base_pom_version": version,
                "java": "21", "files": {name: digest(path) for name, path in files.items()}}
    result = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, capture_output=True, text=True)
    manifest["base_commit"] = result.stdout.strip() if result.returncode == 0 else "unknown"
    manifest["note"] = "Source may contain uncommitted changes; file SHA-256 values identify this exact artifact. Not the published v0.2.0 distribution."
    output = root / "target/vortex-local-alpha.zip"
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as bundle:
        for name, path in files.items():
            bundle.write(path, name)
        bundle.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        bundle.writestr("start.ps1", "$ErrorActionPreference = 'Stop'\nPush-Location $PSScriptRoot\ntry {\n  & java --enable-preview -jar vortex-app.jar --spring.profiles.active=local\n  if ($LASTEXITCODE -ne 0) { throw 'Vortex failed to start' }\n} finally { Pop-Location }\n")
        bundle.writestr("start.sh", '#!/usr/bin/env bash\nset -euo pipefail\ncd "$(dirname "$0")"\nexec java --enable-preview -jar vortex-app.jar --spring.profiles.active=local\n')
        bundle.writestr("README.txt", "Vortex personal memory - trial alpha\n\n"
                         "Install Java 21, then run: powershell -File start.ps1 (Windows) or bash start.sh.\n"
                         "Memory workbench: http://127.0.0.1:8080/ - click Run demo to see real memory updates and recall.\n"
                         "API documentation: http://127.0.0.1:8080/swagger-ui.html\n"
                         "Memory stays in ./data/vortex across restarts. Stop the process before backing up that directory.\n"
                         "This archive already contains the model; Maven, Docker and external storage are not required.\n\n"
                         f"Python: python -m pip install python/{wheels[0].name}\n"
                         "Demo: python demo.py (in a second terminal while Vortex is running).\n"
                         "Automatic agent: python agent.py --namespace personal (requires model credentials).\n"
                         "Use DEEPSEEK_API_KEY, or MODEL_BASE_URL + MODEL_NAME + MODEL_API_KEY. /new starts a new chat.\n"
                         f'MCP extra: python -m pip install "python/{wheels[0].name}[mcp]"\n'
                         "MCP stdio command: python -m vortex_memory.mcp\n"
                         "Codex CLI: python ops/run-codex-memory.py --namespace personal\n"
                         "Uses your configured Codex model/login and a separate trial workspace; global config is not changed.\n"
                         "The Python agent and Codex share facts when configured with the same namespace.\n"
                         "Optional environment: VORTEX_URL, VORTEX_NAMESPACE, VORTEX_SECURITY_BEARER_TOKEN.\n\n"
                         "Personal loopback use only; shared Bearer token is not multi-user identity.\n"
                         "Existing v0.2.0 images do not include these features. This is a source snapshot, not a published release.\n"
                         "See manifest.json and verification.json for exact artifact hashes and verification scope.\n")
    output.with_suffix(".zip.sha256").write_text(digest(output) + "  " + output.name + "\n", encoding="ascii")
    print(output)
    print(f"{output.stat().st_size / 1024**2:.1f} MiB; model and wheel included")


if __name__ == "__main__":
    main()
