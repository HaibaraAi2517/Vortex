import json
import os
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

from vortex_memory import CompatibleChatModel, ModelError


class Handler(BaseHTTPRequestHandler):
    mode = "stop"
    calls = []

    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        self.calls.append((self.path, self.headers.get("Authorization"), body))
        if self.mode == "redirect":
            self.send_response(302)
            self.send_header("Location", "/leaked")
            self.end_headers()
            return
        result = {"choices": [{"finish_reason": self.mode, "message": {"content": '{"updates":[]}'}}]}
        if self.mode == "invalid":
            result = {"unexpected": "shape"}
        data = json.dumps(result).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class ModelTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def setUp(self):
        Handler.calls.clear()
        Handler.mode = "stop"
        self.model = CompatibleChatModel(f"http://127.0.0.1:{self.server.server_port}/v1", "test-model", api_key="test-secret")

    def test_sends_json_mode_and_utf8_message_to_configured_provider(self):
        self.assertEqual(self.model([{"role": "user", "content": "我住上海"}], json_mode=True), '{"updates":[]}')
        path, auth, body = Handler.calls[0]
        self.assertEqual(path, "/v1/chat/completions")
        self.assertEqual(auth, "Bearer test-secret")
        self.assertEqual(body["response_format"], {"type": "json_object"})
        self.assertEqual(body["messages"][0]["content"], "我住上海")

    def test_truncation_and_tool_calls_are_never_accepted_as_completed_answers(self):
        for mode in ["length", "tool_calls", "content_filter", "invalid"]:
            with self.subTest(mode=mode):
                Handler.mode = mode
                with self.assertRaises(ModelError):
                    self.model([{"role": "user", "content": "hello"}])

    def test_redirect_does_not_forward_credentials_or_retry(self):
        Handler.mode = "redirect"
        with self.assertRaises(ModelError) as error:
            self.model([{"role": "user", "content": "hello"}])
        self.assertNotIn("test-secret", str(error.exception))
        self.assertEqual(len(Handler.calls), 1)

    def test_explicit_provider_does_not_inherit_another_providers_key(self):
        with patch.dict(os.environ, {"MODEL_BASE_URL": "http://localhost:11434/v1", "MODEL_NAME": "local",
                                     "DEEPSEEK_API_KEY": "private-key"}, clear=True):
            self.assertEqual(CompatibleChatModel.from_env().api_key, "")


if __name__ == "__main__":
    unittest.main()
