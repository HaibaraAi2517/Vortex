"""Real BGE + packaged Java service + Python/MCP + forced restart. No external LLM or Docker.

Uses a new isolated directory and loopback port; never touches the user's Vortex data.
Run with the interpreter where ./sdk/python[mcp] is installed.
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import secrets
import signal
import socket
import subprocess
import sys
import time
import zipfile
from urllib.error import URLError
from urllib.request import urlopen
from uuid import uuid4


def file_sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as binary:
        for chunk in iter(lambda: binary.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_checksum(file):
    actual = file_sha256(file)
    checksum = file.with_name(file.name + ".sha256").read_text(encoding="ascii").split()
    if checksum != [actual, file.name]:
        raise ValueError(f"Checksum or filename mismatch: {file}")
    return actual


def check_bundle(archive, destination):
    archive_hash = verify_checksum(archive)
    with zipfile.ZipFile(archive) as bundle:
        names = [item.filename for item in bundle.infolist() if not item.is_dir()]
        manifest = json.loads(bundle.read("manifest.json"))
        if len(names) != len(set(names)) or set(names) != set(manifest["files"]) | {"manifest.json"}:
            raise ValueError("ZIP contents and manifest inventory differ")
        for name in names:
            relative = PurePosixPath(name)
            if (relative.is_absolute() or ".." in relative.parts or ":" in name or "\\" in name
                    or relative.as_posix() != name):
                raise ValueError(f"Invalid ZIP entry: {name}")
            extracted = Path(bundle.extract(name, destination))
            if name != "manifest.json" and file_sha256(extracted) != manifest["files"][name]:
                raise ValueError(f"Mismatched file hash: {name}")
    if "GITHUB_SHA" in os.environ and manifest["base_commit"] != os.environ["GITHUB_SHA"]:
        raise ValueError("Package commit differs from the workflow checkout")
    return archive_hash, manifest


def bundle_command(app, env, bundled_runtime):
    if os.name != "nt":
        return ["bash", str(app / "start.sh")]
    if bundled_runtime:
        if not (app / "runtime/bin/java.exe").is_file():
            raise ValueError("Windows x64 distribution must include Java")
        # A clean Windows PC has no Maven, Python, or JDK on its launcher PATH.
        system = Path(os.environ["SystemRoot"])
        env["PATH"] = os.pathsep.join(map(str, (system / "System32", system, system / "System32/WindowsPowerShell/v1.0")))
        env.pop("JAVA_HOME", None)
    env["VORTEX_NO_PAUSE"] = "1"
    env["VORTEX_GATE_LAUNCHER"] = str(app / "Start Vortex.cmd")
    # cmd /s /c removes the outer quotes. The inner quotes protect paths containing spaces.
    return f'"{os.environ["ComSpec"]}" /d /s /c ""%VORTEX_GATE_LAUNCHER%" -NoBrowser"'


def workbench(url):
    with urlopen(url + "/", timeout=10) as response:
        html = response.read().decode("utf-8")
    if '<title>Vortex' not in html or 'id="recall-form"' not in html:
        raise ValueError("Missing memory workbench at /")
    for asset, types in (("/workbench/app.js", {"text/javascript", "application/javascript"}),
                         ("/workbench/app.css", {"text/css"})):
        if asset not in html:
            raise ValueError(f"Workbench does not reference {asset}")
        with urlopen(url + asset, timeout=10) as response:
            if response.headers.get_content_type() not in types or not response.read(128).strip():
                raise ValueError(f"Workbench returned an invalid asset: {asset}")


def profile_context(client):
    name, coffee = "我叫雾杉七九", "我喜欢无糖燕麦拿铁"
    for key, content in (("user.name", name), ("user.preference.coffee", coffee)):
        client.remember(key, content, expected_revision=0)
    cases = [("我叫什么？", [name]), ("照我平时的口味给我推荐一杯咖啡。", [coffee]),
             ("写一条咖啡自取订单，带上我的取餐称呼，按我平时的口味来。", [name, coffee]),
             ("润色这句小说对白：「我叫林澈，我最爱双份浓缩。」", [])]
    for query, wanted in cases:
        hits = client.recall(query, for_agent=True)["fragments"]
        actual = [hit["fragment"]["content"] for hit in hits]
        if sorted(actual) != sorted(wanted):
            raise ValueError(f"Wrong /context selection for {query!r}: {actual}")


async def check_mcp(url, namespace, token):
    from mcp import ClientSession, StdioServerParameters
    from mcp.client.stdio import stdio_client
    config = StdioServerParameters(command=sys.executable, args=["-m", "vortex_memory.mcp"], env={
        **os.environ, "VORTEX_URL": url, "VORTEX_NAMESPACE": namespace, "VORTEX_SECURITY_BEARER_TOKEN": token})
    async with stdio_client(config) as (reader, writer):
        async with ClientSession(reader, writer) as session:
            await session.initialize()
            listed = await session.list_tools()
            assert len(listed.tools) == 6
            empty = await session.call_tool("get_memory", {"key": "mcp.preference"})
            assert not empty.isError
            initial = json.loads("".join(part.text for part in empty.content if part.type == "text"))
            assert initial["status"] == "NOT_FOUND" and initial["revision"] == 0
            created = await session.call_tool("remember", {"key": "mcp.preference", "content": "我偏爱无糖咖啡",
                                                           "expected_revision": initial["revision"]})
            assert not created.isError, created
            result = await session.call_tool("recall", {"query": "喜欢什么咖啡"})
            assert not result.isError
            text = "".join(part.text for part in result.content if part.type == "text")
            assert "无糖咖啡" in text and "embedding" not in text
            current = await session.call_tool("get_memory", {"key": "mcp.preference"})
            assert not current.isError
            deleted = await session.call_tool("forget_memory", {"key": "mcp.preference", "expected_revision": 1})
            assert not deleted.isError


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    artifact = parser.add_mutually_exclusive_group()
    artifact.add_argument("--jar", type=Path)
    artifact.add_argument("--bundle", type=Path, help="Extract and verify the trial ZIP from its own working directory")
    parser.add_argument("--output", type=Path, default=Path("target/local-memory-smoke.json"))
    parser.add_argument("--bundled-runtime", action="store_true", help="Start the Windows ZIP without system Java available")
    parser.add_argument("--mcp", action="store_true")
    parser.add_argument("--distractors", type=int, default=300)
    args = parser.parse_args()
    if args.bundled_runtime and (not args.bundle or os.name != "nt"):
        parser.error("--bundled-runtime requires a Windows host and --bundle")
    if not __debug__:
        parser.error("Run verification without Python -O")
    repo = Path(__file__).resolve().parent.parent
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    run_dir = output.parent / ("local-memory-smoke-" + uuid4().hex[:12])
    run_dir.mkdir()
    app_cwd = repo
    if args.bundle:
        app_cwd = run_dir / "Vortex 安装测试"
        bundle_hash, manifest = check_bundle(args.bundle.resolve(), app_cwd)
        if args.bundled_runtime:
            lock = json.loads((repo / "ops/temurin-windows-x64.json").read_text(encoding="utf-8"))
            if manifest.get("runtime") != lock or manifest.get("platform") != "windows-x64":
                raise ValueError("Package runtime differs from the pinned Windows runtime")
            source = args.bundle.resolve().with_name(lock["source"]["name"])
            if verify_checksum(source) != lock["source"]["sha256"]:
                raise ValueError("The corresponding Temurin source differs from the lock file")
        jar = app_cwd / "vortex-app.jar"
        wheels = list((app_cwd / "python").glob("*.whl"))
        if len(wheels) != 1:
            raise ValueError("The bundle must contain exactly one Python wheel")
        # Both this client and its MCP child must load the shipped SDK, not an editable checkout.
        wheel = str(wheels[0])
        sys.path.insert(0, wheel)
        os.environ["PYTHONPATH"] = wheel + (os.pathsep + os.environ["PYTHONPATH"] if os.environ.get("PYTHONPATH") else "")
    else:
        jars = list((repo / "vortex-app/target").glob("vortex-app-*-exec.jar"))
        jar = args.jar.resolve() if args.jar else max(jars, key=lambda path: path.stat().st_mtime)
    from vortex_memory import VortexClient, VortexConflictError
    from vortex_memory import client as sdk_client, mcp as sdk_mcp
    if args.bundle:
        assert str(sdk_client.__file__).startswith(wheel), sdk_client.__file__
        assert str(sdk_mcp.__file__).startswith(wheel), sdk_mcp.__file__
    model_path = app_cwd / "models/bge-small-zh"
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    url = f"http://127.0.0.1:{port}"
    token = secrets.token_hex(32)
    namespace = "smoke-" + uuid4().hex[:12]
    client = VortexClient(url, namespace=namespace, token=token, timeout=60)
    process = None
    log_file = None
    report = {"checks": [], "distractors": args.distractors, "embedding": "real BGE-Small-ZH ONNX", "external_llm_used": False,
              "mcp_checked": False}
    report["python_imports"] = {"client": sdk_client.__file__, "mcp": sdk_mcp.__file__}
    if args.bundle:
        report["bundle_sha256"] = bundle_hash
        report["base_commit"] = manifest["base_commit"]
        report["bundled_runtime_only"] = args.bundled_runtime
        if args.bundled_runtime:
            report["runtime_source"] = lock["source"]
        report["wheel_sha256"] = file_sha256(wheels[0])
        report["manifest_files_verified"] = len(manifest["files"])
    start = time.monotonic()

    def record(name):
        report["checks"].append(name)
        print("PASS", name, flush=True)

    def start_server(attempt):
        nonlocal process, log_file
        log_file = (run_dir / f"server-{attempt}.log").open("wb")
        env = {**os.environ, "VORTEX_DATA_DIR": str(run_dir / "data"), "VORTEX_SECURITY_BEARER_TOKEN": token}
        if args.bundle:
            env.update(VORTEX_HTTP_PORT=str(port), VORTEX_SECURITY_ENABLED="true",
                       VORTEX_SECURITY_NAMESPACE_PATTERNS=namespace + "*", VORTEX_SECURITY_AUDIT_ENABLED="false",
                       VORTEX_SECURITY_REQUESTS_PER_MINUTE="5000")
            for var in ("BGE_MODEL_PATH", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
                env.pop(var, None)
            command = bundle_command(app_cwd, env, args.bundled_runtime)
        else:
            command = ["java", "--enable-preview", "-Xms128m", "-Xmx768m", "-Dfile.encoding=UTF-8", "-jar", str(jar),
                       "--spring.profiles.active=local", f"--server.port={port}", "--server.address=127.0.0.1",
                       "--vortex.security.enabled=true", f"--vortex.security.namespace-patterns={namespace}*",
                       "--vortex.security.requests-per-minute=5000", "--vortex.security.audit-enabled=false",
                       "--management.endpoint.health.probes.enabled=true", f"--vortex.kernel.embedding.bge.model-path={model_path}"]
        process = subprocess.Popen(command, cwd=run_dir if args.bundle else app_cwd, env=env,
                                   stdout=log_file, stderr=subprocess.STDOUT, start_new_session=os.name != "nt",
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(f"Vortex startup failed; inspect {run_dir / f'server-{attempt}.log'}")
            try:
                with urlopen(url + "/actuator/health/readiness", timeout=2) as response:
                    if response.status == 200:
                        return
            except (URLError, OSError):
                pass
            time.sleep(0.25)
        raise TimeoutError("Vortex did not become ready within 120 seconds")

    def stop_server():
        nonlocal process, log_file
        if process and process.poll() is None:
            # Crash only the process tree that this verifier started, including the launcher child.
            if args.bundle and os.name == "nt":
                subprocess.run(["taskkill", "/f", "/t", "/pid", str(process.pid)], check=True,
                               stdout=subprocess.DEVNULL, timeout=30)
            elif os.name != "nt":
                os.killpg(process.pid, signal.SIGKILL)
            else:
                process.kill()
            process.wait(timeout=30)
        if log_file:
            log_file.close()
        process = None

    def texts(query, mode="HYBRID"):
        return [hit["fragment"]["content"] for hit in client.recall(query, mode=mode, top_k=10)["fragments"]]

    try:
        start_server(1)
        record("packaged service starts without external storage")
        workbench(url)
        record("workbench HTML, JavaScript and stylesheet served at the advertised URL")
        profile = VortexClient(url, namespace=namespace + "-profile", token=token, timeout=60)
        profile_context(profile)
        record("/context selects name, coffee, both or neither according to the current task")
        for index in range(args.distractors):
            client.store(f"会议档案 {index}：今天整理了项目的例行会议记录。", tags=["noise"])
        coffee = "我喜欢喝美式咖啡，不加糖"
        appended = client.store(coffee, tags=["preferences"])
        assert coffee in texts("用户喜欢什么咖啡", "KEYWORD_ONLY")
        record("Chinese keyword retrieval beyond 256 memories" if args.distractors >= 256 else "Chinese keyword retrieval")
        assert coffee in texts("用户喜欢什么咖啡", "HYBRID")
        record("natural-language hybrid retrieves coffee preference")
        assert texts("虫洞量子夸克", "KEYWORD_ONLY") == []
        record("keyword-only does not return unrelated hot memories")
        first = client.remember("home.city", "我住北京")
        texts("用户住在哪里")
        second = client.remember("home.city", "我已经搬到上海了", expected_revision=first["revision"])
        for mode in ("KEYWORD_ONLY", "VECTOR_ONLY", "HYBRID"):
            recalled = texts("用户现在住上海还是北京", mode)
            assert "我已经搬到上海了" in recalled and "我住北京" not in recalled
        record("all retrieval modes exclude the replaced cached revision")
        try:
            client.remember("home.city", "过期更新北京", expected_revision=1)
            raise AssertionError("Stale write was accepted")
        except VortexConflictError as conflict:
            assert conflict.current_revision == 2
        record("stale update receives HTTP 409")
        stop_server()
        start_server(2)
        assert client.get_fact("home.city")["revision"] == second["revision"]
        assert profile.context("我叫什么？") == "我叫雾杉七九"
        assert coffee in texts("用户喜欢什么咖啡", "KEYWORD_ONLY")
        assert "我住北京" not in texts("用户现在住哪里")
        record("acknowledged facts and full-text index survive forced process restart")
        if args.mcp:
            asyncio.run(check_mcp(url, namespace, token))
            report["mcp_checked"] = True
            record("official MCP stdio client calls real packaged service")
        report["status"] = "passed"
    except BaseException as exc:
        report["status"] = "failed"
        report["error"] = str(exc)
        raise
    finally:
        stop_server()
        report["elapsed_seconds"] = round(time.monotonic() - start, 2)
        report["jar_sha256"] = file_sha256(jar)
        output.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        print("Report:", output, flush=True)


if __name__ == "__main__":
    main()
