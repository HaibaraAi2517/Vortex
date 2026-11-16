"""Run Codex with Vortex MCP without modifying the user's global configuration.

Use --message for a fresh non-interactive turn; --isolate limits that turn to the
Vortex tools for an auditable acceptance run. Model/provider/auth stay unchanged.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from typing import Iterable
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parent.parent
SERVER = "vortex-memory"
DISABLED_FEATURES = (
    "memories", "apps", "plugins", "shell_tool", "multi_agent", "multi_agent_v2",
    "browser_use", "computer_use", "image_generation", "hooks",
)


def resolve_codex(path: str | None = None) -> list[str]:
    """Resolve a native executable or an npm shim without invoking a shell."""
    candidate = Path(path).expanduser() if path else None
    if candidate is None or not candidate.is_file():
        located = shutil.which(path or "codex")
        if not located:
            raise ValueError("Codex was not found. Install Codex CLI or pass --codex /path/to/codex.exe.")
        candidate = Path(located)
    candidate = candidate.resolve()
    suffix = candidate.suffix.lower()
    if suffix in (".exe", ".com"):
        return [str(candidate)]

    script = (candidate if suffix == ".js" else
              candidate.parent / "node_modules" / "@openai" / "codex" / "bin" / "codex.js")
    if script.is_file():
        node = next((p for p in (candidate.parent / "node.exe", candidate.parent / "node")
                     if p.is_file()), None)
        if node is None:
            located_node = shutil.which("node")
            node = Path(located_node) if located_node else None
        if node is None:
            raise ValueError("The Codex npm launcher requires Node. Pass --codex with a native executable instead.")
        return [str(node.resolve()), str(script.resolve())]

    if suffix in (".ps1", ".cmd", ".bat", ".js") or os.name == "nt":
        raise ValueError("Cannot safely resolve the Codex launcher. Pass --codex with a native codex.exe; shell shims are not executed.")
    return [str(candidate)]


def prepare_workspace(path: Path, *, root: Path | None = None) -> Path:
    """Copy the example policy, refusing to replace a different or linked file."""
    root = (root or ROOT).resolve()
    workspace = Path(path).expanduser().resolve()
    if workspace == root or workspace in root.parents:
        raise ValueError("Use a separate trial directory; the repository and its ancestors cannot receive this AGENTS.md.")
    expected = (root / "examples" / "codex-memory" / "AGENTS.md").read_bytes()
    workspace.mkdir(parents=True, exist_ok=True)
    destination = workspace / "AGENTS.md"
    if destination.is_symlink():
        raise ValueError("The trial AGENTS.md is a symbolic link; choose a new directory.")
    try:
        with destination.open("xb") as output:
            output.write(expected)
    except FileExistsError:
        if not destination.is_file() or destination.read_bytes() != expected:
            raise ValueError("The trial directory already has a different AGENTS.md; choose a new directory. It was not overwritten.") from None
    return workspace


def load_user_mcp_names(config_path: Path | None = None) -> list[str]:
    """Read names only. Never copy, log, or serialize provider credentials."""
    if config_path is None:
        codex_config_dir = Path(os.environ.get("CODEX_HOME", str(Path.home() / ".codex")))
        config_path = codex_config_dir / "config.toml"
    config_path = Path(config_path)
    if not config_path.is_file():
        return []
    try:
        import tomllib
    except ImportError:
        try:
            import tomli as tomllib
        except ImportError:
            raise ValueError("--isolate needs Python 3.11+ or `python -m pip install tomli` on Python 3.10.") from None
    try:
        configuration = tomllib.loads(config_path.read_text(encoding="utf-8-sig"))
    except (OSError, UnicodeError, tomllib.TOMLDecodeError):
        raise ValueError("Cannot read the Codex TOML configuration; isolated execution was not started.") from None
    servers = configuration.get("mcp_servers", {})
    if not isinstance(servers, dict):
        raise ValueError("Codex mcp_servers must be a TOML table; isolated execution was not started.")
    return sorted(servers)


def _toml(value) -> str:
    # These values are command arguments, never shell text. JSON string escaping
    # also yields TOML basic strings for the validated paths and settings here.
    if isinstance(value, dict):
        return "{" + ",".join(f"{key}={_toml(item)}" for key, item in value.items()) + "}"
    if isinstance(value, (list, tuple)):
        return "[" + ",".join(_toml(item) for item in value) + "]"
    return json.dumps(value, ensure_ascii=False)


def build_command(*, codex: list[str], python: str, workspace: Path, url: str,
                  namespace: str, message: str | None = None, isolate: bool = False,
                  json_output: bool = False, output_last_message: Path | None = None,
                  other_mcp_names: Iterable[str] | None = None,
                  mcp_cwd: Path | None = None) -> list[str]:
    """Build argv. For a message turn the caller supplies UTF-8 prompt bytes on stdin.

    No model, provider, auth, or CODEX_HOME override is added. The complete Vortex
    table replaces a potentially incompatible server with the same name.
    """
    if not codex:
        raise ValueError("A resolved Codex executable is required.")
    if message is None and (isolate or json_output or output_last_message is not None):
        raise ValueError("--isolate, --json, and --output-last-message require --message.")
    if message is not None and not message.strip():
        raise ValueError("--message must not be empty.")
    parsed = urlsplit(url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("VORTEX_URL must be an HTTP(S) URL without credentials, query, or fragment.")
    if not namespace.strip() or len(namespace) > 128 or any(ord(char) < 32 or ord(char) == 127 for char in namespace):
        raise ValueError("A namespace of 1..128 characters without control characters is required.")

    command = [*codex]
    if message is not None:
        command += ["exec", "--skip-git-repo-check", "--ephemeral"]
    command += ["-C", str(Path(workspace).expanduser().resolve())]
    if json_output:
        command.append("--json")
    if output_last_message is not None:
        command += ["--output-last-message", str(Path(output_last_message).expanduser().resolve())]

    overrides: list[tuple[str, object]] = []
    if isolate:
        command += ["--sandbox", "read-only"]
        overrides.extend(("features." + feature, False) for feature in DISABLED_FEATURES)
        overrides.extend([
            ("approval_policy", "never"), ("web_search", "disabled"),
            ("memories.use_memories", False), ("memories.generate_memories", False),
            ("notify", []),
        ])
        names = load_user_mcp_names() if other_mcp_names is None else other_mcp_names
        for name in sorted(set(names)):
            if not re.fullmatch(r"[A-Za-z0-9_-]+", name):
                raise ValueError("A configured MCP server name cannot be safely disabled by Codex -c. Rename it to letters, digits, '_' or '-' before --isolate.")
            if name != SERVER:
                overrides.append((f"mcp_servers.{name}.enabled", False))

    vortex_server = {
        "command": str(Path(python).expanduser().resolve()),
        "args": ["-m", "vortex_memory.mcp"],
        "cwd": str((mcp_cwd or ROOT).resolve()),
        "enabled": True,
        "required": True,
        "startup_timeout_sec": 30,
        "tool_timeout_sec": 45,
        "env": {"VORTEX_URL": url, "VORTEX_NAMESPACE": namespace},
        "env_vars": ["VORTEX_SECURITY_BEARER_TOKEN"],
    }
    if isolate:
        # The acceptance explicitly exercises these two mutations in its fresh
        # namespace. Keep every other tool's approval policy with the host.
        vortex_server["tools"] = {
            "remember": {"approval_mode": "approve"},
            "retract_memory": {"approval_mode": "approve"},
        }
    overrides.append((f"mcp_servers.{SERVER}", vortex_server))
    for key, value in overrides:
        command += ["-c", f"{key}={_toml(value)}"]
    if message is not None:
        command.append("-")
    return command


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default=os.environ.get("VORTEX_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--namespace", default=os.environ.get("VORTEX_NAMESPACE", "personal"))
    parser.add_argument("--codex", help="Codex native executable or resolvable npm launcher")
    parser.add_argument("--python", default=sys.executable, help="Python interpreter with vortex-agent-memory[mcp] installed")
    parser.add_argument("--workdir", type=Path, help="Separate trial directory; defaults to a new system temporary directory")
    parser.add_argument("--message", help="One fresh exec turn; prompt is sent on stdin, not argv")
    parser.add_argument("--isolate", action="store_true", help="Disable other tools/memories for acceptance; requires --message")
    parser.add_argument("--json", action="store_true", dest="json_output", help="Emit Codex JSONL events; requires --message")
    parser.add_argument("--output-last-message", type=Path, help="Save final answer; requires --message")
    parser.add_argument("--timeout", type=float, help="Optional process timeout in seconds")
    args = parser.parse_args(argv)
    try:
        if args.timeout is not None and args.timeout <= 0:
            raise ValueError("--timeout must be positive.")
        python = Path(args.python).expanduser()
        if not python.is_file():
            raise ValueError("--python must name an installed Python interpreter.")
        executable = resolve_codex(args.codex)
        # Validate all configuration before creating the isolated policy file.
        workspace = args.workdir or Path(tempfile.gettempdir()) / ("vortex-codex-memory-" + os.urandom(8).hex())
        command = build_command(codex=executable, python=str(python), workspace=workspace,
                                url=args.url, namespace=args.namespace, message=args.message,
                                isolate=args.isolate, json_output=args.json_output,
                                output_last_message=args.output_last_message)
        workspace = prepare_workspace(workspace)
        if args.output_last_message is not None:
            args.output_last_message.expanduser().resolve().parent.mkdir(parents=True, exist_ok=True)
        print(f"Vortex trial directory: {workspace}", file=sys.stderr)
        print(f"Vortex namespace: {args.namespace}", file=sys.stderr)
        result = subprocess.run(command, input=args.message.encode("utf-8") if args.message is not None else None,
                                shell=False, timeout=args.timeout)
        return result.returncode
    except (ValueError, OSError) as error:
        parser.exit(2, f"Vortex Codex launcher: {error}\n")
    except subprocess.TimeoutExpired:
        parser.exit(124, "Codex timed out; a memory write may already have committed. Read the fact before retrying.\n")
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
