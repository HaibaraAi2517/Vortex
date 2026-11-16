"""Live cross-process Python/Spring AI conversation verification. Uses a real configured LLM.

Build Spring AI first: mvn -f examples/spring-ai-integration/pom.xml test dependency:build-classpath -Dmdep.outputFile=target/live-classpath.txt
"""
import argparse
import asyncio
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid

from vortex_memory import VortexClient


QUESTION = "请只根据你记得的事实回答：我现在长期住在哪个城市？平时最喜欢喝什么咖啡？不知道就明确说不知道。"
CITY, COFFEE = "user.home.city", "user.preference.coffee"


async def check_mcp(url, namespace):
    from mcp import ClientSession, StdioServerParameters
    from mcp.client.stdio import stdio_client
    params = StdioServerParameters(command=sys.executable, args=["-m", "vortex_memory.mcp"],
                                  env={**os.environ, "VORTEX_URL": url, "VORTEX_NAMESPACE": namespace})
    async with stdio_client(params) as (reader, writer):
        async with ClientSession(reader, writer) as session:
            await session.initialize()
            result = await session.call_tool("recall", {"query": QUESTION})
            if result.isError:
                raise AssertionError("MCP recall failed")
            text = "".join(item.text for item in result.content if item.type == "text")
            assert all(word in text for word in ("上海", "燕麦", "拿铁")), "MCP did not see the updated facts"
            current = await session.call_tool("get_memory", {"key": CITY})
            assert not current.isError
            return {"status": "passed", "recalled": json.loads(text),
                    "current_fact": json.loads("".join(c.text for c in current.content if c.type == "text"))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default="agent-auto-" + uuid.uuid4().hex[:10])
    parser.add_argument("--output", type=Path, default=Path("target/agent-memory-live.json"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    cp_file = root / "examples/spring-ai-integration/target/live-classpath.txt"
    if not cp_file.is_file():
        raise SystemExit("Build the Spring AI example and dependency classpath first (see --help).")
    classpath = str(root / "examples/spring-ai-integration/target/classes") + os.pathsep + cp_file.read_text().strip()
    url = os.environ.get("VORTEX_URL", "http://127.0.0.1:8080")
    client = VortexClient(url, namespace=args.namespace, token=os.environ.get("VORTEX_SECURITY_BEARER_TOKEN"))
    if client.get_fact(CITY) or client.get_fact(COFFEE):
        raise SystemExit("Verification requires a fresh namespace; no existing facts were changed.")
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {"status": "running", "namespace": args.namespace, "url": url, "steps": [],
              "started_at": datetime.now(timezone.utc).isoformat(),
              "scope": "Real LLM; each turn in a new Python process or JVM; actual persisted facts; no seeded answers."}
    sources = [*sorted((root / "sdk/python/src/vortex_memory").glob("*.py")),
               *sorted((root / "examples/spring-ai-integration/src/main/java").rglob("*.java")),
               root / "examples/personal-memory/agent.py", Path(__file__).resolve()]
    report["source_sha256"] = {str(path.relative_to(root)).replace("\\", "/"): hashlib.sha256(path.read_bytes()).hexdigest()
                               for path in sources}
    report["java_class_sha256"] = {str(path.relative_to(root)).replace("\\", "/"): hashlib.sha256(path.read_bytes()).hexdigest()
                                   for path in sorted((root / "examples/spring-ai-integration/target/classes").rglob("*.class"))}

    def save():
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    def turn(runtime, name, message, *, namespace=None, expected=()):
        step_file = output.parent / (output.stem + "-" + name + ".json")
        namespace = namespace or args.namespace
        env = {**os.environ, "VORTEX_URL": url, "VORTEX_NAMESPACE": namespace,
               "AGENT_MESSAGE": message, "AGENT_REPORT": str(step_file), "PYTHONIOENCODING": "utf-8"}
        command = ([sys.executable, "-X", "utf8", str(root / "examples/personal-memory/agent.py"),
                    "--namespace", namespace, "--message", message, "--output", str(step_file)] if runtime == "python"
                   else ["java", "-Dfile.encoding=UTF-8", "-cp", classpath,
                         "com.vortex.examples.springai.AutomaticMemoryDemo"])
        print(f"Running {name} ({runtime}, fresh process)...", flush=True)
        start = time.monotonic()
        result = subprocess.run(command, cwd=root, env=env, capture_output=True, timeout=240)
        if result.returncode:
            text = (result.stdout + result.stderr).decode("utf-8", errors="replace")
            for key in ("MODEL_API_KEY", "DEEPSEEK_API_KEY", "VORTEX_SECURITY_BEARER_TOKEN"):
                if env.get(key):
                    text = text.replace(env[key], "[REDACTED]")
            raise RuntimeError(f"{name} failed: {text[-4000:]}")
        evidence = json.loads(step_file.read_text(encoding="utf-8"))
        report["steps"].append({"name": name, "runtime": runtime, "seconds": round(time.monotonic() - start, 2), **evidence})
        save()
        assert not evidence["errors"], f"{name}: learning failure"
        for word in expected:
            assert word in evidence["answer"], f"{name}: answer missing {word}"
        print(f"  {evidence['answer']} | commits={len(evidence['updates'])}", flush=True)
        return evidence

    try:
        baseline = turn("python", "baseline", QUESTION, namespace=args.namespace + "-empty")
        assert not baseline["recalled"] and not baseline["updates"]
        assert "北京" not in baseline["answer"] and "上海" not in baseline["answer"], "Baseline invented a residence"
        turn("python", "learn", "我目前长期住在北京，平时最喜欢无糖美式。")
        assert client.get_fact(CITY)["revision"] == client.get_fact(COFFEE)["revision"] == 1
        before = turn("spring-ai", "recall-before", QUESTION, expected=("北京", "美式"))
        assert not before["updates"], "A question should not create facts"
        turn("spring-ai", "update", "我已经搬到上海长期居住了。咖啡偏好也改了，现在最喜欢燕麦拿铁，不再喝美式。")
        assert client.get_fact(CITY)["revision"] == client.get_fact(COFFEE)["revision"] == 2
        after = turn("python", "recall-after", QUESTION, expected=("上海", "燕麦", "拿铁"))
        assert not after["updates"]
        assert all("北京" not in item["content"] for item in after["recalled"]), "Obsolete city leaked into recall"
        negative = turn("python", "third-party-and-plan", "我朋友住在广州，喜欢卡布奇诺。我下周可能去杭州出差，这些都不是我的长期偏好。")
        assert not negative["updates"], "Third-party or hypothetical facts were saved as user facts"
        turn("spring-ai", "recall-final", QUESTION, expected=("上海", "燕麦", "拿铁"))
        report["facts"] = [client.get_fact(key) for key in (CITY, COFFEE)]
        report["history"] = {key: client.history(key) for key in (CITY, COFFEE)}
        assert all(fact["revision"] == 2 for fact in report["facts"]), "A read caused an unexpected revision"
        report["mcp"] = asyncio.run(check_mcp(url, args.namespace))
        report["status"] = "passed"
    except Exception as exc:
        report["status"], report["failure"] = "failed", str(exc)
        save()
        raise
    save()
    print(f"PASS: {output}\nWorkbench: {url}/?namespace={args.namespace}", flush=True)


if __name__ == "__main__":
    main()
