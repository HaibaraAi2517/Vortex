import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

from vortex_memory import VortexClient, VortexConflictError, VortexHTTPError


class Handler(BaseHTTPRequestHandler):
    calls = []

    def log_message(self, *args):
        pass

    def do_GET(self):
        self.handle_request()

    def do_PUT(self):
        self.handle_request()

    def do_POST(self):
        self.handle_request()

    def do_DELETE(self):
        self.handle_request()

    def handle_request(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        payload = json.loads(body) if body else None
        self.calls.append((self.command, self.path, self.headers.get("Authorization"), payload))
        status = 200
        path = urlsplit(self.path).path
        params = parse_qs(urlsplit(self.path).query)
        if payload and payload.get("key") == "malformed-success":
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"not valid JSON")
            return
        if params.get("key") == ["missing"]:
            status, result = 404, {}
        elif payload and payload.get("expectedRevision") == 99:
            status, result = 409, {"error": "MEMORY_REVISION_CONFLICT", "currentRevision": 2}
        elif params.get("key") == ["denied"]:
            status, result = 403, {"message": "Access denied"}
        elif params.get("key") == ["redirect"]:
            self.send_response(302)
            self.send_header("Location", "/api/v1/memory/facts?namespace=leak&key=bad")
            self.end_headers()
            return
        elif path.endswith(("/recall", "/context")):
            result = {"fragments": [{"fragment": {"id": "m1", "content": "上海", "tags": [], "embedding": [1, 0]}, "score": 0.9}], "totalTokens": 2}
        elif path.endswith("/history"):
            result = [{"key": "city", "revision": 1, "content": "北京"}]
        else:
            result = {"key": "city", "revision": 2, "content": "上海", "status": "ACTIVE"}
        encoded = json.dumps(result, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)


class ClientTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def setUp(self):
        Handler.calls.clear()
        self.client = VortexClient(self.url, namespace="personal/张三", token="test-secret")

    def test_unicode_and_revision_are_preserved(self):
        self.client.remember("user.home.city", "我搬到上海了", expected_revision=1)
        method, path, auth, body = Handler.calls[-1]
        self.assertEqual(method, "PUT")
        self.assertEqual(body["namespace"], "personal/张三")
        self.assertEqual(body["content"], "我搬到上海了")
        self.assertEqual(body["expectedRevision"], 1)
        self.assertEqual(auth, "Bearer test-secret")

    def test_conflict_is_not_retried(self):
        with self.assertRaises(VortexConflictError) as error:
            self.client.remember("city", "old", expected_revision=99)
        self.assertEqual(error.exception.current_revision, 2)
        self.assertEqual(len(Handler.calls), 1)

    def test_invalid_success_response_is_an_unknown_write_outcome(self):
        from vortex_memory import VortexConnectionError
        with self.assertRaises(VortexConnectionError):
            self.client.remember("malformed-success", "上海")
        self.assertEqual(len(Handler.calls), 1)

    def test_missing_is_distinct_from_auth_failure(self):
        self.assertIsNone(self.client.get_fact("missing"))
        with self.assertRaises(VortexHTTPError) as error:
            self.client.get_fact("denied")
        self.assertEqual(error.exception.status, 403)

    def test_redirect_never_forwards_token(self):
        with self.assertRaises(VortexHTTPError):
            self.client.get_fact("redirect")
        self.assertEqual(len(Handler.calls), 1)

    def test_history_encodes_key_and_pagination(self):
        self.client.history("a&key=other", limit=2, before_revision=8)
        params = parse_qs(urlsplit(Handler.calls[-1][1]).query)
        self.assertEqual(params["key"], ["a&key=other"])
        self.assertEqual(params["beforeRevision"], ["8"])

    def test_recall_context_and_forget(self):
        self.assertEqual(self.client.context("住哪里？"), "上海")
        self.client.forget("city", expected_revision=2)
        self.assertEqual(Handler.calls[-1][0], "DELETE")
        self.assertIn("expectedRevision=2", Handler.calls[-1][1])

    def test_rejects_invalid_config_and_naive_expiry(self):
        from datetime import datetime
        for url in ["file:///tmp/x", "http://user:secret@localhost", "http://localhost?token=x"]:
            with self.assertRaises(ValueError):
                VortexClient(url)
        with self.assertRaises(ValueError):
            self.client.remember("key", "value", expires_at=datetime.now())


if __name__ == "__main__":
    unittest.main()
