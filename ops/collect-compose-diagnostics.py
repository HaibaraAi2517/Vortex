"""Collect bounded, sanitized Docker diagnostics without changing container state.

The exit status is deliberately zero: the workflow retains the original failure.
metadata.json describes collection failures; it never declares the app healthy.
"""
import argparse
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import re
import subprocess
import time


MAX_CONTAINERS = 32
MAX_TEXT_CHARS = 65_536
LOG_LINES = 250
CONTAINER_ID = re.compile(r"[0-9a-f]{12,64}\Z", re.IGNORECASE)
SECRET_NAME = re.compile(r"TOKEN|PASSWORD|SECRET|API[_-]?KEY", re.IGNORECASE)
PS_FIELDS = ("ID", "Name", "Service", "State", "Status", "Health", "ExitCode", "RunningFor")
STATE_FIELDS = ("Status", "Running", "Paused", "Restarting", "OOMKilled", "Dead", "Pid",
                "ExitCode", "Error", "StartedAt", "FinishedAt")


class Redactor:
    def __init__(self, environment):
        values = set()
        for key, value in environment.items():
            if value and SECRET_NAME.search(key):
                values.add(value)
                # Logs sometimes contain JSON-escaped environment values.
                values.add(json.dumps(value, ensure_ascii=False)[1:-1])
                values.add(json.dumps(value, ensure_ascii=True)[1:-1])
        self.values = sorted(values, key=len, reverse=True)

    def text(self, value):
        if isinstance(value, bytes):
            value = value.decode("utf-8", errors="replace")
        text = value or ""
        for secret in self.values:
            text = text.replace(secret, "[REDACTED]")
        text = re.sub(r"(?im)(using generated security password:\s*)[^\r\n]+",
                      r"\1[REDACTED]", text)
        text = re.sub(r"(?i)\b(Bearer|Basic)\s+[^\s\"',;]+", r"\1 [REDACTED]", text)
        text = re.sub(
            r"(?i)([\"']?[\w.-]*(?:token|password|secret|api[_-]?key)[\w.-]*[\"']?\s*[:=]\s*)"
            r"(?:\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'|[^\s,;}\]\"']+)",
            r"\1[REDACTED]", text)
        text = re.sub(r"(?i)(--(?:password|token|secret|api-key|requirepass)(?:=|\s+))\S+",
                      r"\1[REDACTED]", text)
        text = re.sub(r"(?i)(https?://)[^\s/@:]+:[^\s/@]+@", r"\1[REDACTED]@", text)
        return text

    def tree(self, value):
        if isinstance(value, str):
            return self.text(value)
        if isinstance(value, list):
            return [self.tree(item) for item in value]
        if isinstance(value, dict):
            return {key: self.tree(item) for key, item in value.items()}
        return value


def _decode(value):
    return value.decode("utf-8", errors="replace") if isinstance(value, bytes) else value or ""


def _scalars(value, fields):
    # Never retain Config, Env, Command, Labels, or unexpected nested objects.
    return {key: value[key] for key in fields if key in value
            and (value[key] is None or isinstance(value[key], (str, bool, int, float)))}


def _ps(raw):
    if not raw.strip():
        return []
    try:
        rows = json.loads(raw)
    except json.JSONDecodeError:
        rows = [json.loads(line) for line in raw.splitlines() if line.strip()]
    rows = rows if isinstance(rows, list) else [rows]
    if any(not isinstance(row, dict) or not any(key in row for key in PS_FIELDS) for row in rows):
        raise ValueError("unexpected_shape")
    return [_scalars(row, PS_FIELDS) for row in rows]


def _state(raw):
    value = json.loads(raw)
    if not isinstance(value, dict) or not any(key in value for key in STATE_FIELDS):
        raise ValueError("unexpected_shape")
    state = _scalars(value, STATE_FIELDS)
    health = value.get("Health")
    if isinstance(health, dict):
        state["Health"] = _scalars(health, ("Status", "FailingStreak"))
        entries = health.get("Log")
        if isinstance(entries, list):
            state["Health"]["Log"] = [
                _scalars(entry, ("Start", "End", "ExitCode", "Output"))
                for entry in entries[-LOG_LINES:] if isinstance(entry, dict)]
    return state


def _execute(command, timeout, environment):
    record = {"command": command, "timeout_seconds": timeout, "exit_code": None,
              "status": "failed", "error_type": None}
    stdout = stderr = ""
    started = time.monotonic()
    try:
        result = subprocess.run(command, capture_output=True, check=False, shell=False,
                                timeout=timeout, env=environment)
        stdout, stderr = _decode(result.stdout), _decode(result.stderr)
        record["exit_code"] = result.returncode
        record["status"] = "succeeded" if result.returncode == 0 else "failed"
        record["error_type"] = None if result.returncode == 0 else "nonzero_exit"
    except subprocess.TimeoutExpired as error:
        stdout, stderr = _decode(error.stdout), _decode(error.stderr)
        record["error_type"] = "timeout"
    except FileNotFoundError:
        record["error_type"] = "executable_not_found"
    except PermissionError:
        record["error_type"] = "permission_denied"
    except OSError:
        record["error_type"] = "os_error"
    except Exception:
        # Exceptions can embed credentials, command arguments, or provider bodies.
        record["error_type"] = "unexpected_error"
    record["duration_ms"] = round((time.monotonic() - started) * 1000)
    return record, stdout, stderr


def collect_diagnostics(compose_file, output, *, timeout=30, environment=None):
    environment = dict(os.environ if environment is None else environment)
    redactor = Redactor(environment)
    output = Path(output)
    report = {"schema_version": 1, "scope": "diagnostic_collection_only",
              "captured_at_utc": datetime.now(timezone.utc).isoformat(),
              "compose_file": str(compose_file), "all_commands_succeeded": False,
              "metadata_saved": False, "commands": [], "output_errors": []}
    try:
        output.mkdir(parents=True, exist_ok=True)
    except OSError:
        report["output_errors"].append({"file": "output_directory", "error_type": "write_error"})
        return redactor.tree(report)

    def save(name, text):
        try:
            (output / name).write_text(text, encoding="utf-8")
            return True
        except OSError:
            report["output_errors"].append({"file": name, "error_type": "write_error"})
            return False

    def bounded(text, *, lines=None):
        text = redactor.text(text)
        parts = text.splitlines(keepends=True)
        truncated = lines is not None and len(parts) > lines
        if truncated:
            text = "".join(parts[-lines:])
        if len(text) > MAX_TEXT_CHARS:
            text = text[:MAX_TEXT_CHARS]
            truncated = True
        return text + ("\n[TRUNCATED]\n" if truncated else ""), truncated

    def capture(name, command, kind):
        record, raw_stdout, raw_stderr = _execute(command, timeout, environment)
        record["name"] = name
        result = None
        issue = None
        if kind in ("ps", "state"):
            try:
                result = _ps(raw_stdout) if kind == "ps" else _state(raw_stdout)
                stdout = json.dumps(redactor.tree(result), ensure_ascii=False, indent=2) + "\n"
            except (ValueError, TypeError):
                # Invalid structural output is never copied verbatim into evidence.
                stdout = json.dumps({"output_omitted": "invalid_structured_output"}) + "\n"
                issue = "invalid_structured_output"
            extension = "json"
        elif kind == "ids":
            lines = [line.strip() for line in raw_stdout.splitlines() if line.strip()]
            valid = list(dict.fromkeys(line for line in lines if CONTAINER_ID.fullmatch(line)))
            if len(valid) != len(set(lines)):
                issue = "invalid_container_ids"
            if len(valid) > MAX_CONTAINERS:
                issue = "container_limit_exceeded"
            result = valid[:MAX_CONTAINERS]
            stdout = redactor.text("\n".join(result) + ("\n" if result else ""))
            extension = "txt"
        else:
            stdout, record["stdout_truncated"] = bounded(raw_stdout, lines=LOG_LINES)
            extension = "log"
        if issue:
            record["output_error_type"] = issue
            record["status"] = "failed"
            record["error_type"] = record["error_type"] or issue
        stderr, record["stderr_truncated"] = bounded(raw_stderr)
        record["stdout_file"] = f"{name}.{extension}"
        record["stderr_file"] = f"{name}.stderr.log"
        record["stdout_saved"] = save(record["stdout_file"], stdout)
        record["stderr_saved"] = save(record["stderr_file"], stderr)
        report["commands"].append(record)
        return result

    compose = ["docker", "compose", "-f", str(compose_file)]
    capture("compose-ps", compose + ["ps", "--all", "--format", "json"], "ps")
    container_ids = capture("compose-ids", compose + ["ps", "-aq"], "ids") or []
    report["container_count"] = len(container_ids)
    for index, container_id in enumerate(container_ids, 1):
        capture(f"container-{index:02d}-state",
                ["docker", "inspect", "--format", "{{json .State}}", container_id], "state")
    capture("vortex", compose + ["logs", "--no-color", "--timestamps", "--tail", str(LOG_LINES), "vortex"], "logs")
    report["all_commands_succeeded"] = (not report["output_errors"]
                                         and all(item["status"] == "succeeded" for item in report["commands"]))
    report["metadata_saved"] = True
    if not save("metadata.json", json.dumps(redactor.tree(report), ensure_ascii=False, indent=2) + "\n"):
        report["metadata_saved"] = False
        report["all_commands_succeeded"] = False
    return redactor.tree(report)


def _timeout(value):
    try:
        result = float(value)
    except ValueError:
        raise argparse.ArgumentTypeError("timeout must be a finite number from 0 to 120 seconds") from None
    if not math.isfinite(result) or not 0 < result <= 120:
        raise argparse.ArgumentTypeError("timeout must be a finite number greater than 0 and at most 120 seconds")
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file", default="docker-compose.quickstart.yml")
    parser.add_argument("--output", default="target/quickstart-diagnostics")
    parser.add_argument("--timeout", type=_timeout, default=30)
    args = parser.parse_args(argv)
    try:
        report = collect_diagnostics(args.file, args.output, timeout=args.timeout)
        summary = {"metadata_saved": report["metadata_saved"],
                   "all_commands_succeeded": report["all_commands_succeeded"],
                   "command_count": len(report["commands"]),
                   "failed_command_count": sum(item["status"] != "succeeded" for item in report["commands"]),
                   "output_error_count": len(report["output_errors"])}
    except Exception:
        summary = {"metadata_saved": False, "all_commands_succeeded": False,
                   "error_type": "collection_error"}
    print(json.dumps(summary))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
