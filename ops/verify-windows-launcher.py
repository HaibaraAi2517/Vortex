"""Exercise the Windows double-click launcher in an extracted trial ZIP, on an isolated port and profile.

    python ops/verify-windows-launcher.py target/vortex-local-alpha.zip
"""
import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile
import time
from urllib.error import URLError
from urllib.request import ProxyHandler, Request, build_opener
import zipfile


def retry_file_operation(operation):
    # Windows can still be unmapping the terminated JVM or scanning its DLLs after taskkill returns.
    deadline = time.monotonic() + 12
    while True:
        try:
            return operation()
        except OSError as error:
            if getattr(error, "winerror", None) not in (5, 32, 33) or time.monotonic() >= deadline:
                raise
            time.sleep(0.25)


@contextmanager
def temporary_trial():
    parent = Path(tempfile.gettempdir()).resolve()
    trial = Path(tempfile.mkdtemp(prefix="Vortex 用户试用 ", dir=parent))
    try:
        yield trial
    finally:
        if trial.resolve() != trial or trial.parent != parent:
            raise RuntimeError(f"Unexpected test cleanup path: {trial}")
        retry_file_operation(lambda: shutil.rmtree(trial))


def verify(bundle):
    http = build_opener(ProxyHandler({}))
    with temporary_trial() as trial:
        original = trial / "第一版"
        upgrade = trial / "新版"
        with zipfile.ZipFile(bundle) as archive:
            archive.extractall(original)
        env = {key: value for key, value in os.environ.items()
               if not key.startswith(("VORTEX_", "SPRING_", "MANAGEMENT_")) and key != "BGE_MODEL_PATH"}
        env.update(LOCALAPPDATA=str(trial / "用户数据"), SERVER_ADDRESS="0.0.0.0", VORTEX_NO_PAUSE="1")
        if (original / "runtime/bin/java.exe").is_file():
            # Nothing in the developer's JDK installation may satisfy this test.
            env.update(PATH=os.environ["SystemRoot"] + r"\System32", JAVA_HOME=str(trial / "no system JDK"))
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            port = reservation.getsockname()[1]
        env["VORTEX_HTTP_PORT"] = str(port)
        base = f"http://127.0.0.1:{port}"
        command = [env["COMSPEC"], "/d", "/c", "call", "Start Vortex.cmd", "-NoBrowser"]

        @contextmanager
        def running(folder):
            with (trial / "launcher.log").open("wb") as output:
                app = subprocess.Popen(command, cwd=folder, env=env, stdin=subprocess.DEVNULL, stdout=output,
                                       stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
                try:
                    deadline = time.monotonic() + 120
                    while time.monotonic() < deadline:
                        if app.poll() is not None:
                            raise RuntimeError((trial / "launcher.log").read_bytes().decode("mbcs", errors="replace")[-5000:])
                        try:
                            with http.open(base + "/actuator/health/readiness", timeout=2) as res:
                                if json.load(res)["status"] == "UP":
                                    break
                        except (OSError, URLError):
                            pass
                        time.sleep(0.4)
                    else:
                        raise TimeoutError("Launcher did not start a ready service")
                    yield
                finally:
                    if app.poll() is None:
                        subprocess.run(["taskkill", "/F", "/T", "/PID", str(app.pid)],
                                       stdout=subprocess.DEVNULL, check=True, timeout=30)
                        app.wait(timeout=30)

        with running(original):
            with http.open(base + "/", timeout=5) as page:
                assert "Vortex" in page.read().decode("utf-8")
            # Windows netstat state labels vary by locale, so compare addresses instead.
            nets = subprocess.check_output(["netstat", "-an", "-p", "tcp"]).decode(errors="replace")
            binds = re.findall(rf"^\s*TCP\s+(\S+:{port})\s+0\.0\.0\.0:0\s", nets, re.MULTILINE | re.I)
            assert binds == [f"127.0.0.1:{port}"], binds
            collision = subprocess.run(command, cwd=original, env=env, stdin=subprocess.DEVNULL,
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=15)
            assert collision.returncode != 0 and str(port).encode() in collision.stdout, "missing occupied-port error"
            facts = base + "/api/v1/memory/facts"
            payload = {"namespace": "launcher-smoke", "key": "user.name", "content": "白桦七号", "expectedRevision": 0}
            with http.open(Request(facts, json.dumps(payload).encode(), {"Content-Type": "application/json"},
                                   method="PUT"), timeout=60) as stored:
                assert json.load(stored)["revision"] == 1
        assert (Path(env["LOCALAPPDATA"]) / "Vortex/data/index").is_dir()
        assert not (original / "data/vortex").exists()
        # Both directories belong exclusively to this test run.
        assert original.resolve().parent == upgrade.resolve().parent == trial.resolve()
        retry_file_operation(lambda: original.rename(upgrade))
        with running(upgrade):
            with http.open(facts + "?namespace=launcher-smoke&key=user.name", timeout=10) as response:
                fact = json.load(response)
                assert fact["revision"] == 1 and fact["content"] == payload["content"]
    print("PASS Windows launcher: ready workbench, loopback only, port collision, stable data after changing folders.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    args = parser.parse_args()
    if os.name != "nt":
        parser.error("Run on Windows")
    verify(args.bundle.resolve())
