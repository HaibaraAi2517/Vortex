"""Negative acceptance cases: a model's claim is never MCP execution evidence."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location(
    "vortex_codex_verification", Path(__file__).resolve().parents[3] / "ops/verify-codex-memory.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


def fact(key=runner.CITY, revision=2, content="用户长期住上海", status="ACTIVE"):
    return {"namespace": "codex-test", "key": key, "revision": revision, "content": content, "status": status,
            "fragmentId": f"fragment-{key}-{revision}", "tags": [], "createdAt": "2026-11-17T00:00:00Z",
            "updatedAt": "2026-11-17T00:00:00Z", "expiresAt": None}


def call(tool="get_memory", key=runner.CITY, value=None, **arguments):
    return {"id": "call-1", "type": "mcp_tool_call", "server": "vortex-memory", "tool": tool,
            "arguments": {"key": key, **arguments}, "status": "completed", "error": None,
            "result": {"structured_content": value if value is not None else fact()}}


def transcript(calls=(), *, extra=(), answer="用户长期住上海，喜欢燕麦拿铁。"):
    events = [{"type": "thread.started", "thread_id": "fresh-thread"}, {"type": "turn.started"}]
    for index, item in enumerate(calls):
        completed = {**item, "id": f"call-{index}"}
        started = {**completed, "status": "in_progress", "result": None}
        events.extend([{"type": "item.started", "item": started},
                       {"type": "item.completed", "item": completed}])
    events.extend(extra)
    events.extend([{"type": "item.completed", "item": {"id": "answer", "type": "agent_message", "text": answer}},
                   {"type": "turn.completed", "usage": {}}])
    return "\n".join(json.dumps(event, ensure_ascii=False) for event in events)


class CodexEvidenceTest(unittest.TestCase):
    def read_step(self, calls):
        step = runner.read_events(transcript(calls))
        step["facts_before"] = {**dict.fromkeys(runner.KEYS), runner.CITY: fact(),
                                runner.COFFEE: fact(runner.COFFEE, content="用户喜欢燕麦拿铁")}
        step["facts_after"] = copy.deepcopy(step["facts_before"])
        return step

    def write_step(self, calls):
        return {"mcp_calls": calls, "facts_before": dict.fromkeys(runner.KEYS),
                "facts_after": {**dict.fromkeys(runner.KEYS), runner.CITY: fact(revision=1)}}

    def test_valid_completed_reads_use_actual_mcp_payload(self):
        calls = [call(), call(key=runner.COFFEE, value=fact(runner.COFFEE, content="用户喜欢燕麦拿铁"))]
        step = self.read_step(calls)
        runner.check_reads(step)
        self.assertEqual(step["thread_id"], "fresh-thread")
        self.assertEqual(runner.tool_value(step["mcp_calls"][0])["revision"], 2)

    def test_recall_can_supply_current_facts_without_direct_key_reads(self):
        item = call(tool="recall", value={"memories": [
            {"id": "city-2", "content": "用户长期住上海", "tags": [], "score": 0.9},
            {"id": "coffee-2", "content": "用户喜欢燕麦拿铁", "tags": [], "score": 0.8}], "totalTokens": 15})
        item["arguments"] = {"query": "住哪里，喜欢喝什么咖啡"}
        runner.check_reads(self.read_step([item]))

    def test_empty_recall_in_an_empty_namespace_is_valid_evidence(self):
        item = call(tool="recall", value={"memories": [], "totalTokens": 0})
        item["arguments"] = {"query": "住哪里，喜欢喝什么咖啡"}
        step = runner.read_events(transcript([item], answer="不知道"))
        step["facts_before"] = dict.fromkeys(runner.KEYS)
        step["facts_after"] = dict.fromkeys(runner.KEYS)
        runner.check_reads(step)

    def test_structured_and_text_only_success_are_supported(self):
        for result in ({"structuredContent": fact()},
                       {"content": [{"type": "text", "text": json.dumps(fact())}]}):
            with self.subTest(result=result):
                item = call()
                item["result"] = result
                self.assertEqual(runner.tool_value(item), fact())

    def test_claimed_tool_call_in_answer_is_not_execution(self):
        step = runner.read_events(transcript(answer='I called get_memory; {"revision":2}'))
        with self.assertRaises(AssertionError):
            runner.check_reads(step)

    def test_failed_or_error_marked_completed_calls_are_rejected(self):
        for mutate in (lambda c: c.update(status="failed"),
                       lambda c: c.update(error={"message": "failed"}),
                       lambda c: c["result"].update(isError=True),
                       lambda c: c["result"].update(is_error=True),
                       lambda c: c.update(server="different-memory")):
            item = call()
            mutate(item)
            with self.subTest(item=item), self.assertRaises(AssertionError):
                runner.read_events(transcript([item]))

    def test_toolerror_text_cannot_count_as_a_successful_read(self):
        item = call()
        item["result"] = {"content": [{"type": "text", "text":
            'Error executing tool get_memory: {"code":"MEMORY_REQUEST_FAILED","outcome":"read_failed"}'}]}
        with self.assertRaises((AssertionError, ValueError)):
            runner.check_reads(self.read_step([item]))

    def test_empty_or_error_object_cannot_count_as_successful_read(self):
        for value in ({}, {"code": "MEMORY_REQUEST_FAILED", "outcome": "read_failed"},
                      {**fact(), "code": "MEMORY_REQUEST_FAILED"}):
            with self.subTest(value=value), self.assertRaises(AssertionError):
                runner.check_reads(self.read_step([call(value=value)]))

    def test_incomplete_mcp_call_invalidates_a_completed_turn(self):
        pending = call(tool="remember", value=None, expected_revision=0)
        pending.update(id="unfinished-write", status="in_progress", result=None)
        with self.assertRaises(AssertionError):
            runner.read_events(transcript([call()], extra=[{"type": "item.started", "item": pending}]))

    def test_unknown_tool_is_not_silently_accepted(self):
        with self.assertRaises(AssertionError):
            runner.read_events(transcript([call(tool="unrecognized_memory_mutation")]))

    def test_unrelated_or_stale_reads_do_not_prove_current_answers(self):
        cases = [
            [call(key="user.name", value=fact("user.name", content="张三"))],
            [call(value=fact(revision=1, content="用户长期住北京"))],
            [call(value=fact(runner.COFFEE, content="用户喜欢燕麦拿铁"))],
        ]
        for calls in cases:
            with self.subTest(calls=calls), self.assertRaises(AssertionError):
                runner.check_reads(self.read_step(calls))

    def test_read_only_turn_cannot_write_or_read_historical_truth(self):
        for tool in ("remember", "retract_memory", "forget_memory", "memory_history"):
            with self.subTest(tool=tool), self.assertRaises(AssertionError):
                runner.check_reads(self.read_step([call(), call(tool=tool)]))

    def test_mutation_requires_read_before_write_and_matching_revision(self):
        read = call(value=fact(revision=0, content=None, status="NOT_FOUND"))
        write = call(tool="remember", value=fact(revision=1), expected_revision=0)
        runner.check_writes(self.write_step([read, write]), "remember", {runner.CITY: 1}, "ACTIVE")
        for calls in ([write], [write, read], [read, write, write],
                      [read, call(tool="remember", value=fact(revision=1), expected_revision=7)],
                      [read, call(tool="forget_memory", value=fact(revision=1), expected_revision=0)]):
            with self.subTest(calls=calls), self.assertRaises(AssertionError):
                runner.check_writes(self.write_step(calls), "remember", {runner.CITY: 1}, "ACTIVE")

    def test_write_cannot_reuse_revision_from_another_keys_response(self):
        wrong_read = call(value=fact(runner.COFFEE, revision=0, content=None, status="NOT_FOUND"))
        write = call(tool="remember", value=fact(revision=1), expected_revision=0)
        with self.assertRaises(AssertionError):
            runner.check_writes(self.write_step([wrong_read, write]), "remember", {runner.CITY: 1}, "ACTIVE")

    def test_read_back_after_successful_write_is_valid(self):
        read = call(value=fact(revision=0, content=None, status="NOT_FOUND"))
        write = call(tool="remember", value=fact(revision=1), expected_revision=0)
        read_back = call(value=fact(revision=1))
        runner.check_writes(self.write_step([read, write, read_back]), "remember", {runner.CITY: 1}, "ACTIVE")

    def test_confirmed_mutation_must_match_read_back_durable_state(self):
        read = call(value=fact(revision=0, content=None, status="NOT_FOUND"))
        write = call(tool="remember", value=fact(revision=1), expected_revision=0)
        step = self.write_step([read, write])
        step["facts_after"][runner.CITY] = fact(revision=2, content="另一条并发写入")
        with self.assertRaises(AssertionError):
            runner.check_writes(step, "remember", {runner.CITY: 1}, "ACTIVE")


if __name__ == "__main__":
    unittest.main()
