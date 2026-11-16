"""Verify real Codex MCP calls and Python automatic memory in fresh processes.

Requires the running Vortex service, an authenticated/configured Codex CLI, this
Python's vortex-agent-memory[mcp] installation, and a real Python chat model.
No user/global Codex configuration is edited. This is scenario acceptance, not
a benchmark against native memories. Failed runs retain their evidence.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import uuid

from vortex_memory import CompatibleChatModel, VortexClient


ROOT = Path(__file__).resolve().parent.parent
CITY, COFFEE = "user.home.city", "user.preference.coffee"
KEYS = (CITY, COFFEE, "user.name", "user.work.role", "user.preference.language")
QUESTION = "请只根据你记得的事实回答：我现在长期住在哪个城市？平时最喜欢喝什么咖啡？不知道就明确说不知道。"
MUTATIONS = {"remember", "retract_memory", "forget_memory"}
TOOLS = MUTATIONS | {"get_memory", "recall", "memory_history"}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def read_events(text: str) -> dict:
    """Accept evidence from completed MCP events, never a model's claimed actions."""
    events = [json.loads(line) for line in text.splitlines() if line.strip()]
    threads = [e["thread_id"] for e in events if e.get("type") == "thread.started"]
    require(len(threads) == 1, "Expected exactly one fresh Codex thread")
    require(not any(e.get("type") in {"turn.failed", "error"} for e in events), "Codex reported a failed turn")
    completed = [e for e in events if e.get("type") == "turn.completed"]
    require(len(completed) == 1, "Codex did not complete exactly one turn")
    items = [e["item"] for e in events if e.get("type") == "item.completed"]
    require(not any(i.get("type") in {"command_execution", "file_change", "web_search"} for i in items),
            "Isolated acceptance unexpectedly used a command, file edit or web search")
    calls = [i for i in items if i.get("type") == "mcp_tool_call"]
    started_calls = [e["item"] for e in events if e.get("type") == "item.started" and e.get("item", {}).get("type") == "mcp_tool_call"]
    require(len({c.get("id") for c in started_calls}) == len(started_calls), "Duplicate MCP start events")
    require(len({c.get("id") for c in calls}) == len(calls), "Duplicate MCP completion events")
    require({c.get("id") for c in started_calls} == {c.get("id") for c in calls}, "MCP call started without a matching completion")
    starts = {c["id"]: c for c in started_calls}
    for call in calls:
        require(all(call.get(field) == starts[call["id"]].get(field) for field in ("server", "tool", "arguments")),
                "MCP start and completion refer to different operations")
        require(call.get("server") == "vortex-memory", "Unexpected MCP server used")
        require(call.get("tool") in TOOLS, "Unexpected MCP tool used")
        require(call.get("status") == "completed" and not call.get("error"), "MCP call failed")
        result = call.get("result")
        require(isinstance(result, dict) and not result.get("isError") and not result.get("is_error"),
                "MCP result is missing or an error")
        value = tool_value(call)
        require("code" not in value and "error" not in value, "MCP returned an error value disguised as success")
        if call["tool"] == "recall":
            require(isinstance(value.get("memories"), list), "Recall did not return memories")
            require(all(isinstance(v, dict) and isinstance(v.get("content"), str) for v in value["memories"]), "Invalid recall candidate")
        elif call["tool"] == "memory_history":
            require(isinstance(value.get("revisions"), list), "History did not return revisions")
        else:
            require(value.get("key") == call.get("arguments", {}).get("key") and isinstance(value.get("key"), str), "MCP returned the wrong key")
            require(type(value.get("revision")) is int and value["revision"] >= 0, "MCP returned an invalid revision")
            require(value.get("status") in {"ACTIVE", "EXPIRED", "RETRACTED", "DELETED", "NOT_FOUND"}, "Invalid fact status")
            require(value.get("status") != "ACTIVE" or isinstance(value.get("content"), str), "Active fact has no content")
    messages = [i["text"] for i in items if i.get("type") == "agent_message"]
    require(bool(messages) and bool(messages[-1].strip()), "Codex did not provide a final answer")
    return {"thread_id": threads[0], "answer": messages[-1], "mcp_calls": calls,
            "usage": completed[0].get("usage", {}),
            "warnings": [i.get("message", "") for i in items if i.get("type") == "error"]}


def tool_value(call: dict) -> dict:
    result = call["result"]
    structured = result.get("structured_content", result.get("structuredContent"))
    if isinstance(structured, dict):
        return structured
    content = "".join(c.get("text", "") for c in result.get("content", []) if c.get("type") == "text")
    value = json.loads(content)
    require(isinstance(value, dict), "Expected a structured MCP value")
    return value


def check_reads(step: dict) -> None:
    calls = step["mcp_calls"]
    require(any(c["tool"] in {"get_memory", "recall"} for c in calls), "Answer did not read Vortex")
    require(not any(c["tool"] in MUTATIONS for c in calls), "A read-only question changed memory")
    require(not any(c["tool"] == "memory_history" for c in calls), "Current answer bypassed lifecycle through history")
    expected = step["facts_before"]
    covered = set()
    for call in calls:
        value = tool_value(call)
        if call["tool"] == "get_memory":
            key = call.get("arguments", {}).get("key")
            require(key in expected, "Read an unrelated key")
            fact = expected[key]
            if fact is None:
                require(value.get("status") == "NOT_FOUND" and value.get("revision") == 0, "Missing fact was not read as missing")
            else:
                require(all(value.get(field) == fact.get(field) for field in ("key", "content", "revision", "status")), "Read does not match persisted state")
            covered.add(key)
        elif call["tool"] == "recall":
            require(isinstance(value.get("memories"), list), "Recall result missing memories")
            current = {key: fact["content"] for key, fact in expected.items() if fact and fact["status"] == "ACTIVE"}
            contents = {m["content"] for m in value["memories"]}
            require(contents.issubset(set(current.values())), "Recall returned unrelated or inactive content")
            covered.update(key for key in (CITY, COFFEE) if key not in current or current[key] in contents)
    require({CITY, COFFEE}.issubset(covered), "Answer was not grounded in reads of both requested facts")


def check_writes(step: dict, tool: str, revisions: dict[str, int], status: str) -> None:
    """Check read-before-write and exactly one committed operation per expected key."""
    seen_reads: dict[str, int] = {}
    mutations: dict[str, int] = {}
    known_state = dict(step["facts_before"])
    for call in step["mcp_calls"]:
        arguments, value = call.get("arguments", {}), tool_value(call)
        key = arguments.get("key")
        if call["tool"] == "get_memory":
            require(value.get("key") == key and key in step["facts_before"], "Mutation read the wrong key")
            previous = known_state[key]
            if previous is None:
                require(value.get("status") == "NOT_FOUND" and value.get("revision") == 0, "Create did not read a missing fact")
            else:
                require(all(value.get(field) == previous.get(field) for field in ("key", "content", "revision", "status")),
                        "Mutation read does not match prior persisted state")
            seen_reads[key] = value.get("revision")
        elif call["tool"] in MUTATIONS:
            require(call["tool"] == tool and key in revisions, "Unexpected memory mutation")
            require(key not in mutations, "A fact was mutated more than once")
            expected = revisions[key] - 1
            require(seen_reads.get(key) == expected and arguments.get("expected_revision") == expected,
                    "Mutation did not use the revision actually read")
            require(value.get("key") == key and value.get("revision") == revisions[key] and value.get("status") == status,
                    "Tool did not confirm the expected persisted revision/status")
            actual = step["facts_after"][key]
            require(actual and all(actual.get(field) == value.get(field) for field in ("key", "content", "revision", "status")),
                    "MCP commit did not match the service's persisted state")
            mutations[key] = value["revision"]
            known_state[key] = value
    require(mutations == revisions, "Missing confirmed memory writes")


def redact(text: str) -> str:
    # Avoid echoing environment credentials from a failing child process.
    for key, value in os.environ.items():
        if value and any(word in key.upper() for word in ("TOKEN", "SECRET", "API_KEY")):
            text = text.replace(value, "[REDACTED]")
    return text


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default=os.environ.get("VORTEX_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--namespace", default="codex-live-" + uuid.uuid4().hex[:10])
    parser.add_argument("--output", type=Path, default=Path("target/codex-memory-live.json"))
    parser.add_argument("--codex", help="Codex executable or supported npm shim")
    parser.add_argument("--timeout", type=float, default=240)
    args = parser.parse_args()
    require(args.timeout > 0, "timeout must be positive")
    spec = importlib.util.spec_from_file_location("vortex_codex_launcher", ROOT / "ops/run-codex-memory.py")
    launcher = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(launcher)
    codex = launcher.resolve_codex(args.codex)
    names = launcher.load_user_mcp_names()
    model = CompatibleChatModel.from_env()  # Check settings before any memory mutation.
    client = VortexClient(args.url, namespace=args.namespace, token=os.environ.get("VORTEX_SECURITY_BEARER_TOKEN"))
    require(not any(client.get_fact(key) for key in KEYS), "Use a fresh namespace; existing facts were not changed")
    output = args.output.resolve()
    require(not output.exists(), "Output already exists; choose another --output to preserve previous evidence")
    output.parent.mkdir(parents=True, exist_ok=True)
    evidence_dir = output.parent / (output.stem + "-evidence")
    evidence_dir.mkdir(exist_ok=False)
    # Only the answer-free policy is present in this directory. Event logs and
    # expected answers stay outside the model's workspace, shell tools disabled.
    workspace = launcher.prepare_workspace(Path(tempfile.mkdtemp(prefix="vortex-codex-")), root=ROOT)
    version = subprocess.run([*codex, "--version"], capture_output=True, timeout=20, check=True).stdout.decode("utf-8").strip()
    report = {"status": "running", "namespace": args.namespace, "url": args.url,
              "started_at": datetime.now(timezone.utc).isoformat(), "codex_version": version,
              "python_model": model.model, "workspace": str(workspace), "steps": [],
              "scope": "Real Codex CLI + MCP and real Python LLM. Fresh processes, Codex ephemeral sessions and native memories disabled. Explicit project policy; no per-turn tool coaching or seeded answers.",
              "answer_checks": "Keyword presence and obsolete-city exclusion only; semantic correctness requires reviewing the saved answers. Not an automated semantic judge.",
              "limitations": ["Scenario acceptance, not comparative accuracy or a production SLA.",
                              "MCP invocation is model-controlled under the supplied project policy.",
                              "Configured Codex provider/model are retained; Python model can differ."]}
    sources = [Path(__file__).resolve(), ROOT / "ops/run-codex-memory.py", ROOT / "examples/codex-memory/AGENTS.md",
               ROOT / "examples/personal-memory/agent.py", *sorted((ROOT / "sdk/python/src/vortex_memory").glob("*.py"))]
    report["source_sha256"] = {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
    report["workspace_policy_sha256"] = hashlib.sha256((workspace / "AGENTS.md").read_bytes()).hexdigest()
    require(report["workspace_policy_sha256"] == report["source_sha256"]["examples/codex-memory/AGENTS.md"], "Workspace policy differs from source")
    try:
        import tomllib
    except ImportError:
        import tomli as tomllib
    settings_path = Path(os.environ.get("CODEX_HOME", str(Path.home() / ".codex"))) / "config.toml"
    settings = tomllib.loads(settings_path.read_text(encoding="utf-8-sig")) if settings_path.is_file() else {}
    report["codex_configured"] = {key: settings.get(key) for key in ("model", "model_provider", "model_reasoning_effort")}
    # Check the actual installed modules from both process working directories.
    # Merely hashing the checkout could otherwise validate a stale installed wheel.
    module_probe = ("import hashlib,importlib,json,pathlib; names=['client','mcp','agent','model']; "
                    "modules={n:pathlib.Path(importlib.import_module('vortex_memory.'+n).__file__).resolve() for n in names}; "
                    "print(json.dumps({n:{'path':str(p),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for n,p in modules.items()}))")
    imports = {}
    for directory in (ROOT, workspace):
        probe = subprocess.run([sys.executable, "-X", "utf8", "-c", module_probe], cwd=directory,
                               capture_output=True, timeout=20, check=True)
        values = json.loads(probe.stdout)
        for name, value in values.items():
            source = ROOT / "sdk/python/src/vortex_memory" / (name + ".py")
            require(Path(value["path"]).resolve() == source.resolve() and value["sha256"] == hashlib.sha256(source.read_bytes()).hexdigest(),
                    "Installed Python/MCP sources differ from the checkout; install the SDK editable before verification")
        imports[str(directory)] = values
    report["runtime_imports"] = imports

    def save() -> None:
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    def snapshot() -> dict:
        return {key: client.get_fact(key) for key in KEYS}

    def turn(runtime: str, name: str, message: str) -> dict:
        step = {"name": name, "runtime": runtime, "user_message": message, "facts_before": snapshot()}
        report["steps"].append(step)
        save()
        start = time.monotonic()
        print(f"Running {name} ({runtime}, fresh process)...", flush=True)
        env = {**os.environ, "PYTHONIOENCODING": "utf-8", "VORTEX_URL": args.url, "VORTEX_NAMESPACE": args.namespace}
        if runtime == "codex":
            command = launcher.build_command(codex=codex, python=sys.executable, workspace=workspace,
                                             url=args.url, namespace=args.namespace, message=message, isolate=True,
                                             json_output=True, output_last_message=evidence_dir / f"{name}-answer.txt",
                                             other_mcp_names=names)
            try:
                process = subprocess.run(command, input=message.encode("utf-8"), capture_output=True,
                                         timeout=args.timeout, cwd=workspace, env=env)
            except subprocess.TimeoutExpired as exc:
                (evidence_dir / f"{name}-events.jsonl").write_text(redact((exc.stdout or b"").decode("utf-8", errors="replace")), encoding="utf-8")
                (evidence_dir / f"{name}-stderr.log").write_text(redact((exc.stderr or b"").decode("utf-8", errors="replace")), encoding="utf-8")
                step.update({"timed_out": True, "seconds": round(time.monotonic()-start, 2), "write_outcome": "unknown"})
                try:
                    step["facts_after"] = snapshot()
                except Exception as read_error:
                    step["snapshot_error"] = redact(str(read_error))
                save()
                raise
            stdout = redact(process.stdout.decode("utf-8", errors="replace"))
            stderr = redact(process.stderr.decode("utf-8", errors="replace"))
            (evidence_dir / f"{name}-events.jsonl").write_text(stdout, encoding="utf-8")
            (evidence_dir / f"{name}-stderr.log").write_text(stderr, encoding="utf-8")
            step.update({"exit_code": process.returncode, "seconds": round(time.monotonic()-start, 2)})
            save()
            require(process.returncode == 0, f"{name}: Codex exited {process.returncode}; see sanitized evidence logs")
            step.update(read_events(stdout))
        else:
            path = evidence_dir / f"{name}.json"
            try:
                process = subprocess.run([sys.executable, "-X", "utf8", str(ROOT / "examples/personal-memory/agent.py"),
                                          "--namespace", args.namespace, "--message", message, "--output", str(path)],
                                         capture_output=True, timeout=args.timeout, cwd=ROOT, env=env)
            except subprocess.TimeoutExpired as exc:
                (evidence_dir / f"{name}-stderr.log").write_text(redact((exc.stderr or b"").decode("utf-8", errors="replace")), encoding="utf-8")
                step.update({"timed_out": True, "seconds": round(time.monotonic()-start, 2), "write_outcome": "unknown"})
                try:
                    step["facts_after"] = snapshot()
                except Exception as read_error:
                    step["snapshot_error"] = redact(str(read_error))
                save()
                raise
            step.update({"exit_code": process.returncode, "seconds": round(time.monotonic()-start, 2)})
            if path.exists():
                step.update(json.loads(path.read_text(encoding="utf-8")))
            (evidence_dir / f"{name}-stderr.log").write_text(redact(process.stderr.decode("utf-8", errors="replace")), encoding="utf-8")
            save()
            require(process.returncode == 0 and not step.get("errors"), f"{name}: Python memory flow failed; see evidence")
        step["facts_after"] = snapshot()
        save()
        print(f"  {step['answer']}", flush=True)
        return step

    def require_words(step: dict, words: tuple[str, ...]) -> None:
        require(all(word in step["answer"] for word in words), f"{step['name']}: final answer missing expected facts")

    def unchanged(step: dict) -> None:
        require(step["facts_before"] == step["facts_after"], f"{step['name']}: read/irrelevant text changed memory")

    try:
        baseline = turn("codex", "empty", QUESTION)
        check_reads(baseline)
        unchanged(baseline)
        require_words(baseline, ("不知道",))
        require(not any(x in baseline["answer"] for x in ("北京", "上海", "美式", "拿铁")), "Empty memory invented test facts")

        learned = turn("codex", "learn", "我目前长期住在北京，平时最喜欢无糖美式。")
        check_writes(learned, "remember", {CITY: 1, COFFEE: 1}, "ACTIVE")
        require("北京" in learned["facts_after"][CITY]["content"] and "美式" in learned["facts_after"][COFFEE]["content"], "Codex stored incorrect facts")

        before = turn("python", "python-recall", QUESTION)
        require_words(before, ("北京", "美式"))
        require(not before["updates"], "Python question wrote memory")
        unchanged(before)

        updated = turn("python", "python-update", "我已经搬到上海长期居住了。咖啡偏好也改了，现在最喜欢燕麦拿铁，不再喝美式。")
        require({item["key"] for item in updated["updates"]} == {CITY, COFFEE}, "Python did not commit both updates")
        require(all(updated["facts_after"][key]["revision"] == 2 for key in (CITY, COFFEE)), "Expected durable v2 facts")

        recalled = turn("codex", "recall-updated", QUESTION)
        check_reads(recalled)
        unchanged(recalled)
        require_words(recalled, ("上海", "燕麦", "拿铁"))
        require("北京" not in recalled["answer"], "Codex reused obsolete city")
        require("北京" not in json.dumps(recalled["mcp_calls"], ensure_ascii=False), "Obsolete city leaked through ordinary tools")

        negative = turn("codex", "third-party-and-plan", "我朋友住在广州，喜欢卡布奇诺。我下周可能去杭州出差，这些都不是我的长期偏好。")
        require(not any(c["tool"] in MUTATIONS for c in negative["mcp_calls"]), "Codex wrote another person's facts or a tentative plan")
        unchanged(negative)

        retracted = turn("codex", "retract", "我之前告知的长期住址已经不可靠，请撤回这条记忆，停止把它作为当前事实，但保留审计历史。咖啡偏好保持不变。")
        check_writes(retracted, "retract_memory", {CITY: 3}, "RETRACTED")
        require(retracted["facts_after"][CITY]["status"] == "RETRACTED", "Retraction was not persisted")
        require(retracted["facts_before"][COFFEE] == retracted["facts_after"][COFFEE], "Retraction changed coffee")

        after = turn("codex", "recall-retracted", QUESTION)
        check_reads(after)
        unchanged(after)
        require_words(after, ("不知道", "燕麦", "拿铁"))
        require(not any(x in after["answer"] for x in ("北京", "上海")), "Codex disclosed an inactive city as an answer")

        python_after = turn("python", "python-recall-retracted", QUESTION)
        require_words(python_after, ("不知道", "燕麦", "拿铁"))
        require(not python_after["updates"], "Python recreated retracted memory")
        require(not any(x in python_after["answer"] for x in ("北京", "上海")), "Python reused inactive city")
        unchanged(python_after)
        threads = [s["thread_id"] for s in report["steps"] if s["runtime"] == "codex"]
        require(len(threads) == len(set(threads)), "Codex reused a conversation")
        report["facts"] = snapshot()
        report["history"] = {key: client.history(key) for key in (CITY, COFFEE)}
        require([x["revision"] for x in report["history"][CITY]] == [3, 2, 1], "City history not retained")
        require([x["revision"] for x in report["history"][COFFEE]] == [2, 1], "Coffee has unexpected revisions")
        # A file edited during the run invalidates source-level reproducibility.
        require(all(hashlib.sha256((ROOT/path).read_bytes()).hexdigest() == sha for path,sha in report["source_sha256"].items()),
                "Sources changed during acceptance; rerun the final version")
        require(hashlib.sha256((workspace / "AGENTS.md").read_bytes()).hexdigest() == report["workspace_policy_sha256"], "Workspace policy changed during acceptance")
        report["status"] = "passed"
    except Exception as exc:
        report["status"], report["failure"] = "failed", redact(str(exc))
        if report["steps"] and "facts_after" not in report["steps"][-1]:
            try:
                report["steps"][-1]["facts_after"] = snapshot()
            except Exception as read_error:
                report["steps"][-1]["snapshot_error"] = redact(str(read_error))
        report["finished_at"] = datetime.now(timezone.utc).isoformat()
        save()
        raise
    report["finished_at"] = datetime.now(timezone.utc).isoformat()
    report["total_seconds"] = round(sum(s["seconds"] for s in report["steps"]), 2)
    report["codex_usage"] = {key: sum(s.get("usage", {}).get(key, 0) for s in report["steps"])
                             for key in ("input_tokens", "cached_input_tokens", "output_tokens", "reasoning_output_tokens")}
    save()
    print(f"PASS: {output}\nWorkbench: {args.url}/?namespace={args.namespace}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
