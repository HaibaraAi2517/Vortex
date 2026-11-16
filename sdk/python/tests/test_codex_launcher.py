"""Launcher safety tests; never start Codex or make a model request."""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

try:
    import tomllib
except ImportError:
    import tomli as tomllib


ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location("vortex_codex_launcher", ROOT / "ops" / "run-codex-memory.py")
launcher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(launcher)


def overrides(command):
    return [command[index + 1] for index, arg in enumerate(command) if arg == "-c"]


class CodexLauncherTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.work = Path(self.directory.name)

    def command(self, **kwargs):
        args = dict(codex=["codex.exe"], python=str(self.work / "python.exe"),
                    workspace=self.work / "trial", url="http://127.0.0.1:8080", namespace="trial-张三")
        args.update(kwargs)
        return launcher.build_command(**args)

    def test_npm_shim_resolves_to_node_without_a_shell(self):
        shim = self.work / "codex.ps1"
        node = self.work / "node.exe"
        script = self.work / "node_modules" / "@openai" / "codex" / "bin" / "codex.js"
        script.parent.mkdir(parents=True)
        for file in (shim, node, script):
            file.write_text("test fixture", encoding="utf-8")
        self.assertEqual(launcher.resolve_codex(str(shim)), [str(node.resolve()), str(script.resolve())])

    def test_unknown_shell_wrapper_is_rejected(self):
        shim = self.work / "codex.cmd"
        shim.write_text("do not execute", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "native codex.exe"):
            launcher.resolve_codex(str(shim))

    def test_native_executable_stays_a_single_argument(self):
        native = self.work / "native folder" / "codex.exe"
        native.parent.mkdir()
        native.touch()
        self.assertEqual(launcher.resolve_codex(str(native)), [str(native.resolve())])

    def test_isolated_turn_preserves_provider_and_passes_only_token_name(self):
        secret = "must-not-appear-in-command"
        with patch.dict(os.environ, {"VORTEX_SECURITY_BEARER_TOKEN": secret}):
            command = self.command(message="普通陈述", isolate=True, json_output=True,
                                   other_mcp_names=["cua_repl", "node_repl", "vortex-memory"])
        values = overrides(command)
        self.assertEqual(command[-1], "-")
        self.assertIn("--ephemeral", command)
        self.assertIn("--sandbox", command)
        self.assertIn("read-only", command)
        self.assertIn("mcp_servers.cua_repl.enabled=false", values)
        self.assertIn("mcp_servers.node_repl.enabled=false", values)
        self.assertIn("memories.use_memories=false", values)
        self.assertIn("memories.generate_memories=false", values)
        for feature in launcher.DISABLED_FEATURES:
            self.assertIn("features." + feature + "=false", values)
        serialized = "\n".join(command)
        self.assertNotIn(secret, serialized)
        self.assertNotIn("普通陈述", serialized)
        self.assertNotIn("--ignore-user-config", command)
        self.assertNotIn("--model", command)
        self.assertFalse(any(value.startswith(("model=", "model_provider=", "model_providers.", "CODEX_HOME=")) for value in values))
        self.assertIn('env_vars=["VORTEX_SECURITY_BEARER_TOKEN"]', serialized)
        self.assertEqual(sum(value.startswith("mcp_servers.vortex-memory=") for value in values), 1)

    def test_interactive_mode_keeps_other_tools_and_approval_preferences(self):
        command = self.command()
        values = overrides(command)
        self.assertNotIn("exec", command)
        self.assertNotIn("--ephemeral", command)
        self.assertFalse(any(value.startswith(("features.", "approval_policy=", "web_search=")) for value in values))
        self.assertEqual(len(values), 1)
        server = tomllib.loads(values[0])["mcp_servers"]["vortex-memory"]
        self.assertNotIn("tools", server)

    def test_isolation_preapproves_only_scoped_save_and_retract_tools(self):
        command = self.command(message="statement", isolate=True, other_mcp_names=[])
        value = next(value for value in overrides(command) if value.startswith("mcp_servers.vortex-memory="))
        server = tomllib.loads(value)["mcp_servers"]["vortex-memory"]
        self.assertEqual(server["tools"], {
            "remember": {"approval_mode": "approve"},
            "retract_memory": {"approval_mode": "approve"},
        })
        self.assertEqual(server["env"]["VORTEX_NAMESPACE"], "trial-张三")
        self.assertNotIn("danger-full-access", command)
        self.assertNotIn("--dangerously-bypass-approvals-and-sandbox", command)

    def test_special_mcp_name_is_rejected_instead_of_creating_a_phantom_server(self):
        with self.assertRaisesRegex(ValueError, "cannot be safely disabled"):
            self.command(message="question", isolate=True, other_mcp_names=["server.with.dot"])

    def test_reads_only_names_without_changing_config(self):
        config = self.work / "config.toml"
        original = '[model_providers.custom]\nexperimental_bearer_token="secret"\n[mcp_servers.a]\ncommand="a"\n[mcp_servers.b]\ncommand="b"\n'
        config.write_text(original, encoding="utf-8")
        self.assertEqual(launcher.load_user_mcp_names(config), ["a", "b"])
        self.assertEqual(config.read_text(encoding="utf-8"), original)

    def test_workspace_policy_reuse_and_overwrite_refusal(self):
        workspace = self.work / "trial"
        launcher.prepare_workspace(workspace)
        expected = (ROOT / "examples" / "codex-memory" / "AGENTS.md").read_bytes()
        self.assertEqual((workspace / "AGENTS.md").read_bytes(), expected)
        launcher.prepare_workspace(workspace)
        (workspace / "AGENTS.md").write_text("user's policy", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "not overwritten"):
            launcher.prepare_workspace(workspace)
        self.assertEqual((workspace / "AGENTS.md").read_text(encoding="utf-8"), "user's policy")
        with self.assertRaisesRegex(ValueError, "separate trial directory"):
            launcher.prepare_workspace(ROOT)

    def test_credentials_in_service_url_and_noninteractive_flags_without_message_are_rejected(self):
        for url in ("https://token@example.test", "http://localhost?token=secret"):
            with self.assertRaises(ValueError):
                self.command(url=url)
        for args in ({"isolate": True}, {"json_output": True}, {"output_last_message": self.work / "answer"}):
            with self.assertRaisesRegex(ValueError, "require --message"):
                self.command(**args)

    def test_tilde_workspace_and_output_paths_are_expanded_consistently(self):
        command = self.command(workspace=Path("~/vortex-launcher-trial"), message="question",
                               output_last_message=Path("~/vortex-launcher-answer.txt"))
        self.assertEqual(command[command.index("-C") + 1], str((Path.home() / "vortex-launcher-trial").resolve()))
        self.assertEqual(command[command.index("--output-last-message") + 1],
                         str((Path.home() / "vortex-launcher-answer.txt").resolve()))

    def test_empty_oversized_or_control_character_namespaces_are_rejected(self):
        for namespace in ("   ", "a" * 129, "a\0b", "a\nb"):
            with self.assertRaisesRegex(ValueError, "1..128"):
                self.command(namespace=namespace)

    def test_cli_sends_utf8_stdin_with_shell_disabled(self):
        native, python = self.work / "codex.exe", self.work / "python.exe"
        native.touch()
        python.touch()
        with patch.object(launcher.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)) as run:
            result = launcher.main(["--codex", str(native), "--python", str(python), "--workdir", str(self.work / "trial"),
                                    "--message", "普通陈述", "--namespace", "test"])
        self.assertEqual(result, 0)
        self.assertEqual(run.call_args.kwargs["input"], "普通陈述".encode("utf-8"))
        self.assertIs(run.call_args.kwargs["shell"], False)
        self.assertNotIn("env", run.call_args.kwargs)


if __name__ == "__main__":
    unittest.main()
