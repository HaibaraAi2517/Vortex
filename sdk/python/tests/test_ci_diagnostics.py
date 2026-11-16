"""Failure diagnostics stay read-only, bounded, honest, and safe to publish."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    "vortex_ci_diagnostics", Path(__file__).resolve().parents[3] / "ops/collect-compose-diagnostics.py")
diagnostics = importlib.util.module_from_spec(spec)
spec.loader.exec_module(diagnostics)
CID = "a" * 64


def completed(stdout="", stderr="", returncode=0):
    return subprocess.CompletedProcess([], returncode, stdout=stdout.encode(), stderr=stderr.encode())


class CiDiagnosticsTest(unittest.TestCase):
    def collect(self, responses, *, environment=None, timeout=7):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(diagnostics.subprocess, "run", side_effect=responses) as run:
                report = diagnostics.collect_diagnostics("docker-compose.quickstart.yml", directory,
                                                         timeout=timeout, environment=environment or {})
            files = {path.name: path.read_text(encoding="utf-8") for path in Path(directory).iterdir()}
        return report, files, run.call_args_list

    def test_only_read_only_commands_and_state_projection_are_collected(self):
        private = "CONFIG_ENV_MUST_NEVER_BE_SAVED"
        state = {"Status": "running", "ExitCode": 0, "OOMKilled": False,
                 "Config": {"Env": ["PRIVATE=" + private]}, "Env": [private],
                 "Health": {"Status": "unhealthy", "FailingStreak": 36,
                            "Log": [{"ExitCode": 1, "Output": "connection refused", "Env": private}]}}
        ps = [{"ID": CID, "Name": "vortex", "State": "running", "Config": state["Config"],
               "Env": [private], "Command": private, "Labels": private}]
        report, files, calls = self.collect([completed(json.dumps(ps)), completed(CID),
                                            completed(json.dumps(state)), completed("startup is still running")])
        self.assertTrue(report["all_commands_succeeded"])
        evidence = "\n".join(files.values())
        self.assertNotIn(private, evidence)
        self.assertNotIn('"Config"', evidence)
        self.assertNotIn('"Env"', evidence)
        self.assertEqual(json.loads(files["container-01-state.json"])["Health"]["Status"], "unhealthy")
        commands = [call.args[0] for call in calls]
        self.assertEqual(commands, [
            ["docker", "compose", "-f", "docker-compose.quickstart.yml", "ps", "--all", "--format", "json"],
            ["docker", "compose", "-f", "docker-compose.quickstart.yml", "ps", "-aq"],
            ["docker", "inspect", "--format", "{{json .State}}", CID],
            ["docker", "compose", "-f", "docker-compose.quickstart.yml", "logs", "--no-color",
             "--timestamps", "--tail", "250", "vortex"],
        ])
        for call in calls:
            self.assertEqual(call.kwargs["timeout"], 7)
            self.assertFalse(call.kwargs["shell"])
            self.assertFalse(call.kwargs["check"])

    def test_environment_generated_and_inline_credentials_are_redacted_everywhere(self):
        environment = {"AUTH_TOKEN": "env-token-12345", "REDIS_PASSWORD": "env-password-12345",
                       "APP_SECRET": "env-secret-12345", "MODEL_API_KEY": 'json-key-"quoted"\nsecond-line',
                       "NORMAL_SETTING": "ordinary-readable-value"}
        generated = "generated-spring-secret-4321"
        inline = "inline-bearer-secret-4321"
        logs = ("\n".join(environment.values()) + "\n" + json.dumps(environment) + "\n"
                + "Using generated security password: " + generated + "\n"
                + "Authorization: Bearer " + inline + "\napi_key=not-in-the-environment\n")
        state = {"Status": "running", "Error": environment["REDIS_PASSWORD"],
                 "Health": {"Status": "unhealthy", "Log": [{"Output": logs, "ExitCode": 1}]}}
        report, files, _ = self.collect([completed("[]"), completed(CID), completed(json.dumps(state)),
                                        completed(logs, "error: " + environment["AUTH_TOKEN"])],
                                       environment=environment)
        evidence = "\n".join(files.values()) + json.dumps(report)
        for key, secret in environment.items():
            if key != "NORMAL_SETTING":
                self.assertNotIn(secret, evidence)
                self.assertNotIn(json.dumps(secret)[1:-1], evidence)
        for secret in (generated, inline, "not-in-the-environment"):
            self.assertNotIn(secret, evidence)
        self.assertIn("ordinary-readable-value", evidence)
        self.assertIn("[REDACTED]", evidence)
        json.loads(files["container-01-state.json"])
        json.loads(files["metadata.json"])

    def test_failed_docker_command_is_recorded_and_later_diagnostics_continue(self):
        report, files, calls = self.collect([completed("", "daemon unavailable", 17), completed(CID),
                                            completed('{"Status":"exited","ExitCode":137,"OOMKilled":true}'),
                                            completed("application exited")])
        self.assertFalse(report["all_commands_succeeded"])
        self.assertEqual(report["commands"][0]["exit_code"], 17)
        self.assertEqual(report["commands"][0]["error_type"], "nonzero_exit")
        self.assertEqual(len(calls), 4)
        self.assertIn("application exited", files["vortex.log"])
        self.assertTrue(json.loads(files["container-01-state.json"])["OOMKilled"])
        self.assertFalse(json.loads(files["metadata.json"])["all_commands_succeeded"])

    def test_timeout_keeps_sanitized_partial_output_and_does_not_stop_collection(self):
        secret = "timeout-secret-98765"
        error = subprocess.TimeoutExpired(["docker"], 7,
                                          output=json.dumps([{"ID": CID, "Status": secret}]).encode(),
                                          stderr=("Authorization: Bearer " + secret).encode())
        report, files, calls = self.collect([error, completed(""), completed("later logs")],
                                            environment={"CI_TOKEN": secret})
        self.assertFalse(report["all_commands_succeeded"])
        self.assertEqual(report["commands"][0]["error_type"], "timeout")
        self.assertIsNone(report["commands"][0]["exit_code"])
        self.assertEqual(len(calls), 3)
        self.assertIn("[REDACTED]", files["compose-ps.json"])
        self.assertNotIn(secret, "\n".join(files.values()))
        self.assertIn("later logs", files["vortex.log"])

    def test_arbitrary_exception_messages_are_never_published(self):
        secret = "EXCEPTION_BODY_WITH_UNREGISTERED_SECRET"
        report, files, calls = self.collect([RuntimeError(secret), FileNotFoundError(secret),
                                            PermissionError(secret)])
        self.assertEqual(len(calls), 3)
        self.assertFalse(report["all_commands_succeeded"])
        self.assertEqual([item["error_type"] for item in report["commands"]],
                         ["unexpected_error", "executable_not_found", "permission_denied"])
        self.assertNotIn(secret, "\n".join(files.values()) + json.dumps(report))

    def test_malformed_inspect_output_is_omitted_instead_of_saved_raw(self):
        private = "UNPARSEABLE_CONFIG_ENV_SECRET"
        report, files, _ = self.collect([completed("[]"), completed(CID),
                                        completed('broken {"Config":{"Env":["' + private + '"]}}'),
                                        completed("still got logs")])
        self.assertFalse(report["all_commands_succeeded"])
        self.assertEqual(report["commands"][2]["error_type"], "invalid_structured_output")
        self.assertEqual(report["commands"][2]["exit_code"], 0)
        self.assertNotIn(private, "\n".join(files.values()))
        self.assertIn("still got logs", files["vortex.log"])

    def test_invalid_container_ids_are_not_passed_to_docker(self):
        report, files, calls = self.collect([completed("[]"), completed(CID + "\n--help\n" + CID),
                                            completed('{"Status":"running"}'), completed("")])
        self.assertFalse(report["all_commands_succeeded"])
        self.assertEqual(report["commands"][1]["error_type"], "invalid_container_ids")
        self.assertEqual(report["container_count"], 1)
        self.assertEqual(len(calls), 4)
        self.assertNotIn("--help", "\n".join(files.values()))

    def test_compose_newline_delimited_json_is_supported(self):
        report, files, _ = self.collect([completed('{"Name":"vortex","State":"running"}\n'
                                                   '{"Name":"minio","State":"running"}\n'),
                                        completed(""), completed("")])
        self.assertTrue(report["all_commands_succeeded"])
        self.assertEqual(len(json.loads(files["compose-ps.json"])), 2)

    def test_logs_are_bounded_even_when_docker_returns_extra_output(self):
        logs = "\n".join(f"line {index}" for index in range(500)) + "\n"
        report, files, _ = self.collect([completed("[]"), completed(""), completed(logs)])
        self.assertTrue(report["commands"][-1]["stdout_truncated"])
        self.assertNotIn("line 0\n", files["vortex.log"])
        self.assertIn("line 499\n", files["vortex.log"])
        self.assertLessEqual(len(files["vortex.log"].splitlines()), 252)

    def test_main_returns_zero_but_reports_collection_failures_without_raw_output(self):
        secret = "NEVER_PRINT_THIS_EXCEPTION"
        with tempfile.TemporaryDirectory() as directory, io.StringIO() as stdout:
            with patch.object(diagnostics.subprocess, "run", side_effect=FileNotFoundError(secret)), \
                    contextlib.redirect_stdout(stdout):
                exit_code = diagnostics.main(["--file", "docker-compose.quickstart.yml", "--output", directory])
            summary = json.loads(stdout.getvalue())
            metadata = json.loads((Path(directory) / "metadata.json").read_text(encoding="utf-8"))
        self.assertEqual(exit_code, 0)
        self.assertTrue(summary["metadata_saved"])
        self.assertFalse(summary["all_commands_succeeded"])
        self.assertEqual(summary["failed_command_count"], 3)
        self.assertFalse(metadata["all_commands_succeeded"])
        self.assertNotIn(secret, json.dumps(summary) + json.dumps(metadata))

    def test_unwritable_output_is_reported_without_exception_details(self):
        secret = "OUTPUT_ERROR_SECRET"
        with patch.object(diagnostics.Path, "mkdir", side_effect=PermissionError(secret)), \
                patch.object(diagnostics.subprocess, "run") as run:
            report = diagnostics.collect_diagnostics("docker-compose.quickstart.yml", "unused", environment={})
        run.assert_not_called()
        self.assertFalse(report["metadata_saved"])
        self.assertFalse(report["all_commands_succeeded"])
        self.assertEqual(report["output_errors"][0]["error_type"], "write_error")
        self.assertNotIn(secret, json.dumps(report))


if __name__ == "__main__":
    unittest.main()
