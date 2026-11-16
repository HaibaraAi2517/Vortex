import importlib.util
from pathlib import Path
import sys
import unittest

spec = importlib.util.spec_from_file_location("vortex_longmemeval", Path(__file__).resolve().parents[3] / "ops/eval-longmemeval.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class FakeClient:
    def __init__(self):
        self.stored = []
        self.deleted = []

    def store(self, content):
        self.stored.append(content)
        return {"fragmentIds": [str(len(self.stored))]}

    def recall(self, question, **kwargs):
        return {"fragments": [{"fragment": {"content": self.stored[0]}}], "totalTokens": 10}

    def delete_fragment(self, fragment_id):
        self.deleted.append(fragment_id)


class NonOracleRunnerTest(unittest.TestCase):
    def record(self):
        return {"question_id": "test", "question": "Where?", "question_date": "2026-11-14",
                "answer": "GOLD_SECRET_CANARY", "answer_session_ids": ["evidence"],
                "haystack_dates": ["2026-01-01", "2026-10-01"],
                "haystack_sessions": [[{"role": "user", "content": "Distractor session", "has_answer": False}],
                                      [{"role": "user", "content": "Current city Shanghai", "has_answer": True}]]}

    def test_keeps_distractor_sessions_and_dates_without_gold_or_evidence_leakage(self):
        contents = list(runner.full_haystack(self.record()))
        self.assertEqual(len(contents), 2)
        self.assertIn("Distractor session", contents[0])
        self.assertIn("2026-10-01", contents[1])
        prompt = runner.answer_prompt(self.record(), {"fragments": [{"fragment": {"content": text}} for text in contents]})
        self.assertNotIn("GOLD_SECRET_CANARY", prompt)
        self.assertNotIn("has_answer", prompt)
        self.assertIn("QUESTION DATE: 2026-11-14", prompt)

    def test_large_turn_is_split_without_losing_content(self):
        record = self.record()
        record["haystack_sessions"] = [[{"role": "user", "content": "字" * 40_001}]]
        contents = list(runner.full_haystack(record))
        self.assertEqual(sum(text.count("字") for text in contents), 40_001)
        self.assertTrue(all(len(text) < 20_000 for text in contents))

    def test_runs_external_answer_command_and_cleans_only_own_fragment_ids(self):
        client = FakeClient()
        prediction, stats = runner.run_case(self.record(), client,
            [sys.executable, "-c", "import sys; p=sys.stdin.read(); assert 'GOLD_SECRET_CANARY' not in p; print('Shanghai')"], 5, 2048, 10)
        self.assertEqual(prediction, {"question_id": "test", "hypothesis": "Shanghai"})
        self.assertEqual(stats["stored_messages"], 2)
        self.assertEqual(client.deleted, ["1", "2"])
