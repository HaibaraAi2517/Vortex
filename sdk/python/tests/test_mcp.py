import importlib.util
import json
import os
import sys
import threading
import unittest
from contextlib import asynccontextmanager
from http.server import ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

import test_client


class McpHandler(test_client.Handler):
    def handle_request(self):
        params = parse_qs(urlsplit(self.path).query)
        if params.get("key") == ["deleted"]:
            self.calls.append((self.command, self.path, self.headers.get("Authorization"), None))
            result = {"key": "deleted", "revision": 4, "content": None, "status": "DELETED"}
            encoded = json.dumps(result).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)
        elif params.get("key") == ["unavailable"] and self.command == "DELETE":
            self.calls.append((self.command, self.path, self.headers.get("Authorization"), None))
            self.send_response(503)
            self.end_headers()
            self.wfile.write(b'{"message":"Unavailable"}')
        else:
            super().handle_request()


def payload(result):
    if result.structuredContent is not None:
        return result.structuredContent
    text = "".join(item.text for item in result.content if item.type == "text")
    # FastMCP prefixes ToolError text; the remaining JSON is stable across SDK versions.
    return json.loads(text[text.index("{"):])


@unittest.skipUnless(importlib.util.find_spec("mcp"), "Install the [mcp] extra to run the protocol test")
class McpProtocolTest(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), McpHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def setUp(self):
        McpHandler.calls.clear()

    @asynccontextmanager
    async def session(self):
        from mcp import ClientSession, StdioServerParameters
        from mcp.client.stdio import stdio_client
        params = StdioServerParameters(command=sys.executable, args=["-m", "vortex_memory.mcp"],
                                      env={**os.environ, "VORTEX_URL": self.url, "VORTEX_NAMESPACE": "personal"})
        async with stdio_client(params) as (reader, writer):
            async with ClientSession(reader, writer) as session:
                initialized = await session.initialize()
                yield session, initialized

    async def test_official_client_negotiates_lists_and_calls_tools_over_stdio(self):
        async with self.session() as (session, initialized):
            self.assertEqual(initialized.serverInfo.name, "Vortex Memory")
            tools = await session.list_tools()
            self.assertEqual({tool.name for tool in tools.tools},
                             {"remember", "recall", "get_memory", "memory_history", "retract_memory", "forget_memory"})
            for tool in tools.tools:
                self.assertNotIn("namespace", tool.inputSchema["properties"])
            remember = next(tool for tool in tools.tools if tool.name == "remember")
            self.assertIn("expected_revision", remember.inputSchema["required"])
            recalled = await session.call_tool("recall", {"query": "住哪里？"})
            self.assertFalse(recalled.isError)
            text = "".join(item.text for item in recalled.content if item.type == "text")
            self.assertIn("上海", text)
            self.assertNotIn("embedding", text)
            created = await session.call_tool("remember", {"key": "city", "content": "上海", "expected_revision": 1})
            self.assertFalse(created.isError)
            self.assertEqual(payload(created)["revision"], 2)
            self.assertEqual(payload(created)["status"], "ACTIVE")
            self.assertEqual(created.structuredContent, payload(created))
            self.assertEqual(McpHandler.calls[-1][3]["namespace"], "personal")
            for name in ("get_memory", "memory_history"):
                self.assertFalse((await session.call_tool(name, {"key": "city"})).isError)
            for name in ("retract_memory", "forget_memory"):
                self.assertFalse((await session.call_tool(name, {"key": "city", "expected_revision": 2})).isError)

    async def test_missing_revision_does_not_attempt_a_write(self):
        async with self.session() as (session, _):
            result = await session.call_tool("remember", {"key": "city", "content": "上海"})
            self.assertTrue(result.isError)
            self.assertEqual(McpHandler.calls, [])

    async def test_conflict_reports_current_revision_without_retrying(self):
        async with self.session() as (session, _):
            result = await session.call_tool("remember", {"key": "city", "content": "stale", "expected_revision": 99})
            self.assertTrue(result.isError)
            error = payload(result)
            self.assertEqual(error["code"], "MEMORY_REVISION_CONFLICT")
            self.assertEqual(error["outcome"], "not_committed")
            self.assertEqual(error["expectedRevision"], 99)
            self.assertEqual(error["currentRevision"], 2)
            self.assertIn("do not blindly retry", error["message"])
            self.assertEqual(len(McpHandler.calls), 1)

    async def test_ambiguous_write_is_an_error_with_unknown_outcome(self):
        async with self.session() as (session, _):
            malformed = await session.call_tool("remember", {"key": "malformed-success", "content": "上海", "expected_revision": 0})
            unavailable = await session.call_tool("forget_memory", {"key": "unavailable", "expected_revision": 2})
            for result in (malformed, unavailable):
                self.assertTrue(result.isError)
                error = payload(result)
                self.assertEqual(error["code"], "WRITE_OUTCOME_UNKNOWN")
                self.assertEqual(error["outcome"], "unknown")
                self.assertIn("get_memory", error["message"])
            self.assertEqual(payload(unavailable)["httpStatus"], 503)
            self.assertEqual(len(McpHandler.calls), 2)

    async def test_missing_deleted_and_inaccessible_are_distinct(self):
        async with self.session() as (session, _):
            missing = await session.call_tool("get_memory", {"key": "missing"})
            deleted = await session.call_tool("get_memory", {"key": "deleted"})
            denied = await session.call_tool("get_memory", {"key": "denied"})
            self.assertFalse(missing.isError)
            self.assertEqual(payload(missing), {"key": "missing", "revision": 0, "status": "NOT_FOUND"})
            self.assertFalse(deleted.isError)
            self.assertEqual(payload(deleted)["revision"], 4)
            self.assertEqual(payload(deleted)["status"], "DELETED")
            self.assertIsNone(payload(deleted)["content"])
            self.assertTrue(denied.isError)
            self.assertEqual(payload(denied)["httpStatus"], 403)
            self.assertEqual(payload(denied)["outcome"], "read_failed")
