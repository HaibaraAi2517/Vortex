"""Guard evaluation validity: no gold leakage, real session resets, and honest failures."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from vortex_memory.agent import LEARNING_PROMPT


spec = importlib.util.spec_from_file_location(
    "vortex_memory_quality", Path(__file__).resolve().parents[3] / "ops/eval-memory-quality.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


def case():
    return {"id": "case-1", "category": "identity", "split": "dev",
            "sessions": [["我叫小舟。", "现在请和我打个招呼。"], ["今天聊点别的。"]],
            "question": "我叫什么？", "expected_answer": "GOLD_ANSWER_SENTINEL",
            "answer_rubric": "GOLD_RUBRIC_SENTINEL",
            "expected_facts": {key: {"status": "ABSENT", "all_of": [], "none_of": []} for key in runner.KEYS},
            "forbidden_current_claims": ["GOLD_FORBIDDEN_SENTINEL"], "expect_abstention": False}


def judgment(**kwargs):
    return {"answer_correct": True, "abstention_correct": True, "stale_claim": False,
            "rationale": "The supplied answer satisfies the fixture.", **kwargs}


class Memory:
    def __init__(self, *args, **kwargs):
        self.facts, self.versions = {}, {}

    def get_fact(self, key):
        return copy.deepcopy(self.facts.get(key))

    def history(self, key, **kwargs):
        return copy.deepcopy(self.versions.get(key, []))

    def recall(self, query, **kwargs):
        return {"fragments": [{"fragment": {"id": key, "content": value["content"]}, "score": 1.0}
                              for key, value in self.facts.items() if value["status"] == "ACTIVE"]}

    def remember(self, key, content, *, expected_revision, tags, expires_at):
        actual = self.facts.get(key, {}).get("revision", 0)
        if actual != expected_revision:
            raise RuntimeError("Test did not expect a concurrent update")
        fact = {"key": key, "content": content, "revision": actual + 1, "status": "ACTIVE",
                "tags": tags, "expiresAt": expires_at}
        self.facts[key] = fact
        self.versions.setdefault(key, []).insert(0, copy.deepcopy(fact))
        return copy.deepcopy(fact)


class Model:
    def __init__(self, *, judge_response=None, extraction=None):
        self.calls = []
        self.judge_response = judge_response if judge_response is not None else json.dumps(judgment())
        self.extraction = extraction

    def __call__(self, messages, *, json_mode=False):
        self.calls.append(copy.deepcopy(messages))
        if messages[0]["content"] == runner.JUDGE_RULES:
            return self.judge_response
        if messages[0]["content"] == LEARNING_PROMPT:
            payload = json.loads(messages[1]["content"])
            if self.extraction:
                return self.extraction(payload)
            if "我叫小舟" in payload["user_message"]:
                return json.dumps({"updates": [{"key": "user.name", "operation": "set", "content": "用户叫小舟",
                                                "evidence": "我叫小舟"}]}, ensure_ascii=False)
            return '{"updates":[]}'
        if messages[0]["content"] == runner.SUMMARY_RULES:
            return "用户叫小舟"
        return "小舟。"


class MemoryQualityTest(unittest.TestCase):
    def execute(self, arm="vortex", *, current_case=None, model=None, memory_factory=Memory):
        return runner.run_case_arm(current_case or case(), arm, url="http://127.0.0.1:1",
                                   model_factory=lambda: model or Model(), memory_factory=memory_factory)

    def test_gold_never_enters_generation_or_extraction_or_summary(self):
        current = case()
        current["expected_facts"]["user.name"] = {"status": "ACTIVE", "all_of": ["GOLD_FACT_SENTINEL"], "none_of": []}
        for arm in runner.ARMS:
            with self.subTest(arm=arm):
                result = self.execute(arm, current_case=current)
                for call in result["model_calls"]:
                    if call["kind"] != "judge":
                        self.assertNotIn("GOLD_", json.dumps(call["messages"]))
                judge_call = result["model_calls"][-1]
                self.assertEqual(judge_call["kind"], "judge")
                payload = json.loads(judge_call["messages"][1]["content"])
                self.assertEqual(payload["reference_answer"], current["expected_answer"])
                self.assertNotIn("arm", payload)
                self.assertNotIn("expected_facts", payload)
                self.assertNotIn("namespace", payload)

    def test_new_session_and_final_question_discard_conversation_history_in_every_arm(self):
        for arm in runner.ARMS:
            with self.subTest(arm=arm):
                result = self.execute(arm)
                answer_calls = [call["messages"] for call in result["model_calls"] if call["kind"] == "answer"]
                user_messages = lambda messages: [item["content"] for item in messages if item["role"] == "user"]
                self.assertEqual(user_messages(answer_calls[1]), ["我叫小舟。", "现在请和我打个招呼。"])
                self.assertEqual(user_messages(answer_calls[2]), ["今天聊点别的。"])
                self.assertEqual(user_messages(answer_calls[3]), ["我叫什么？"])
                final_input = json.dumps(answer_calls[3], ensure_ascii=False)
                if arm == "none":
                    self.assertNotIn("小舟", final_input)
                else:
                    self.assertIn("用户叫小舟", final_input)
                self.assertNotIn("打个招呼", final_input)

    def test_question_is_extracted_through_production_chat_and_mutation_is_visible(self):
        current = case()
        current["question"] = "我是不是住杭州？"
        def bad_extractor(payload):
            if payload["user_message"] == current["question"]:
                return json.dumps({"updates": [{"key": "user.home.city", "operation": "set", "content": "住杭州",
                                                "evidence": "杭州"}]}, ensure_ascii=False)
            return '{"updates":[]}'
        result = self.execute(current_case=current, model=Model(extraction=bad_extractor))
        self.assertEqual(result["question_mutations"], ["user.home.city"])
        self.assertIsNone(result["facts_before_question"]["user.home.city"])
        self.assertEqual(result["facts_after_question"]["user.home.city"]["revision"], 1)
        self.assertTrue(result["fact_checks"]["user.home.city"]["passed"])
        self.assertFalse(result["fact_checks_after_question"]["user.home.city"]["passed"])
        self.assertEqual(result["history"]["user.home.city"][0]["content"], "住杭州")

    def test_invalid_or_inconsistent_judge_output_is_not_counted_as_valid(self):
        malformed = ["not json", "```json\n{}\n```", "[]", "null", "{}",
                     json.dumps(judgment(answer_correct="true")),
                     json.dumps(judgment(answer_correct=1)),
                     json.dumps(judgment(stale_claim=True)),
                     json.dumps(judgment(abstention_correct=False)),
                     json.dumps(judgment(rationale=""))]
        for raw in malformed:
            with self.subTest(raw=raw):
                result = self.execute("none", model=Model(judge_response=raw))
                self.assertFalse(result["success"])
                self.assertIsNone(result["judge_verdict"])
                self.assertEqual(result["errors"][0]["stage"], "judge")
                self.assertEqual(result["model_calls"][-1]["response"], raw)

    def test_execution_and_judge_failures_stay_in_requested_denominator(self):
        first, second, third = case(), case(), case()
        second["id"], third["id"] = "case-2", "case-3"
        ok = self.execute("none", current_case=first)
        bad_judge = self.execute("none", current_case=second, model=Model(judge_response="{}"))
        def unavailable(*args, **kwargs):
            raise TimeoutError("secret provider detail should not be serialized")
        failure = self.execute("none", current_case=third, model=unavailable)
        metrics = runner.aggregate([ok, bad_judge, failure], [first, second, third], ["none"])["none"]
        self.assertEqual(metrics["requested"], 3)
        self.assertEqual(metrics["judged"], 1)
        self.assertEqual(metrics["execution_or_judge_failures"], 2)
        self.assertEqual(metrics["answer_correct_rate_all_requested"], 1 / 3)
        self.assertEqual(metrics["answer_correct_rate_judged_only"], 1)
        self.assertNotIn("secret provider detail", json.dumps(failure))
        self.assertEqual(failure["call_totals"]["count"], 1)
        self.assertEqual(len(failure["turns"]), 1)

    def test_post_answer_extraction_errors_are_preserved_as_run_errors(self):
        result = self.execute(model=Model(extraction=lambda payload: "not json"))
        self.assertIsNotNone(result["answer"])
        self.assertIsNotNone(result["judge_verdict"])
        self.assertFalse(result["success"])
        self.assertEqual(len(result["errors"]), 4)
        self.assertTrue(all(error["stage"] == "extraction" for error in result["errors"]))

    def test_empty_baselines_do_not_receive_perfect_fact_precision(self):
        for arm in ("none", "summary"):
            result = self.execute(arm)
            self.assertIsNone(result["fact_checks"])
            metrics = runner.aggregate([result], [case()], [arm])[arm]
            self.assertIsNone(metrics["fact_constraint_checks"])
            self.assertIsNone(metrics["question_mutation_cases"])

    def test_strict_fact_checks_are_separate_from_semantic_judgment(self):
        result = self.execute()
        self.assertFalse(result["fact_checks"]["user.name"]["passed"])
        self.assertTrue(result["judge_verdict"]["answer_correct"])
        self.assertTrue(result["success"])  # Quality mismatch is not an infrastructure failure.
        actual = dict.fromkeys(runner.KEYS)
        expected = copy.deepcopy(case()["expected_facts"])
        expected["user.home.city"] = {"status": "ACTIVE", "all_of": ["上海"], "none_of": ["北京"]}
        actual["user.home.city"] = {"status": "ACTIVE", "content": "现在住上海，不再住北京"}
        checks = runner.check_facts(expected, actual)
        self.assertFalse(checks["user.home.city"]["passed"])  # Deliberately not a semantic claim detector.

    def test_namespace_collision_refuses_all_model_calls(self):
        used = []
        def occupied(*args, **kwargs):
            used.append(kwargs["namespace"])
            memory = Memory()
            memory.facts["user.name"] = {"status": "ACTIVE", "revision": 1, "content": "Existing data"}
            return memory
        result = self.execute(memory_factory=occupied)
        self.assertTrue(used[0].startswith("eval-quality-"))
        self.assertFalse(result["success"])
        self.assertEqual(result["call_totals"]["count"], 0)
        self.assertEqual(result["final_facts"]["user.name"]["revision"], 1)

    def test_data_validation_rejects_invalid_labels_and_duplicate_ids(self):
        dataset = {"schema_version": 1, "name": "fixture", "cases": [case()]}
        self.assertEqual(len(runner.validate_dataset(dataset)), 1)
        broken = []
        value = copy.deepcopy(dataset)
        value["schema_version"] = True
        broken.append(value)
        value = copy.deepcopy(dataset)
        value["cases"].append(case())
        broken.append(value)
        for field, invalid in [("id", "../escaped"), ("split", "train"), ("sessions", [[""]]),
                               ("expected_facts", {}), ("expect_abstention", "false"),
                               ("answer_rubric", []), ("forbidden_current_claims", "claims")]:
            value = copy.deepcopy(dataset)
            value["cases"][0][field] = invalid
            broken.append(value)
        for value in broken:
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    runner.validate_dataset(value)

    def test_summary_budget_is_enforced_and_truncation_is_recorded(self):
        state = {"summary": ""}
        session = runner.BaselineSession(lambda *a, **k: "answer", lambda *a, **k: "字" * 200, state, 128)
        turn = session.chat("一些信息")
        self.assertEqual(len(state["summary"]), 128)
        self.assertTrue(turn["summary_truncated"])

    def test_summary_can_resolve_references_from_recent_user_messages_without_assistant_evidence(self):
        result = self.execute("summary")
        calls = [call for call in result["model_calls"] if call["kind"] == "summarize"]
        second = json.loads(calls[1]["messages"][1]["content"])
        self.assertEqual(second["current_session_user_messages"], ["我叫小舟。", "现在请和我打个招呼。"])
        final = json.loads(calls[-1]["messages"][1]["content"])
        self.assertEqual(final["current_session_user_messages"], ["我叫什么？"])
        self.assertNotIn("assistant", final)

    def test_dry_run_never_creates_memory_or_calls_model(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "cases.json"
            path.write_text(json.dumps({"schema_version": 1, "name": "fixture", "cases": [case()]}), encoding="utf-8")
            with patch.object(runner.CompatibleChatModel, "from_env", side_effect=AssertionError("must not read model config")), \
                 patch.object(runner, "VortexClient", side_effect=AssertionError("must not use REST")), \
                 patch("builtins.print"):
                self.assertEqual(runner.main(["--dataset", str(path), "--dry-run"]), 0)


if __name__ == "__main__":
    unittest.main()
