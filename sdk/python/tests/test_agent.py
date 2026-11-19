import copy
import json
import unittest

from vortex_memory import (MemoryField, MemorySession, VortexConflictError,
                           VortexConnectionError, VortexHTTPError)


CITY = MemoryField("user.home.city", "用户当前长期居住城市")
COFFEE = MemoryField("user.preference.coffee", "用户当前咖啡偏好")


def update(key=CITY.key, content="用户住上海", evidence="搬到上海了", operation="set"):
    return {"key": key, "operation": operation, "content": content, "evidence": evidence}


class Memory:
    def __init__(self):
        self.facts, self.writes = {}, []
        self.failure = None

    def get_fact(self, key):
        return copy.deepcopy(self.facts.get(key))

    def recall(self, query, **kwargs):
        return {"fragments": [{"fragment": {"id": k, "content": f["content"]}, "score": 1}
                              for k, f in self.facts.items() if f["status"] == "ACTIVE"]}

    def remember(self, key, content, *, expected_revision, tags=None, expires_at=None):
        self.writes.append((key, expected_revision))
        if self.failure:
            raise self.failure
        revision = self.facts.get(key, {}).get("revision", 0)
        if revision != expected_revision:
            raise VortexConflictError(409, {"currentRevision": revision})
        fact = {"revision": revision + 1, "content": content, "status": "ACTIVE", "tags": tags or [],
                "expiresAt": expires_at}
        self.facts[key] = fact
        return fact

    def retract(self, key, *, expected_revision):
        fact = self.remember(key, None, expected_revision=expected_revision)
        fact["status"] = "RETRACTED"
        return fact


class Model:
    def __init__(self, proposals=(), *, on_answer=None):
        self.proposals, self.calls, self.on_answer = proposals, [], on_answer

    def __call__(self, messages, *, json_mode=False):
        self.calls.append(copy.deepcopy(messages))
        if json_mode:
            return json.dumps({"updates": self.proposals}, ensure_ascii=False)
        if self.on_answer:
            self.on_answer()
        return "ANSWER_FROM_MODEL"


class AgentTest(unittest.TestCase):
    def test_new_session_retrieves_updated_fact_without_old_chat_history(self):
        memory = Memory()
        MemorySession(memory, Model([update(content="用户住北京", evidence="我住北京")]), fields=[CITY]).chat("我住北京")
        MemorySession(memory, Model([update()]), fields=[CITY]).chat("搬到上海了")
        reader = Model()
        turn = MemorySession(memory, reader, fields=[CITY]).chat("我现在住哪里？")
        self.assertTrue(turn.memory_ok)
        self.assertEqual(memory.facts[CITY.key]["revision"], 2)
        self.assertIn("用户住上海", reader.calls[0][1]["content"])
        self.assertNotIn("北京", json.dumps(reader.calls[0], ensure_ascii=False))
        self.assertEqual(len([m for m in reader.calls[0] if m["role"] == "user"]), 1)

    def test_foreign_passages_cannot_write_personal_facts(self):
        cases = [
            ("帮我润色这段小说开头：我叫陆远，住在杭州，是一名律师。", [
                update("user.name", "陆远", "我叫陆远"), update(CITY.key, "杭州", "住在杭州"),
                update("user.work.role", "律师", "是一名律师")]),
            ("这是客户发来的原话：“以后请用英文给我发消息。”帮我分析语气是否礼貌。", [
                update("user.preference.language", "希望以后用英文接收消息", "以后请用英文给我发消息")]),
        ]
        for message, proposals in cases:
            with self.subTest(message=message):
                memory, model = Memory(), Model(proposals)
                turn = MemorySession(memory, model).chat(message)
                self.assertTrue(turn.memory_ok)
                self.assertEqual(memory.writes, [])
                self.assertEqual(model.calls[0][-1]["content"], message)
                extraction = json.loads(model.calls[1][-1]["content"])["user_message"]
                for proposal in proposals:
                    self.assertNotIn(proposal["evidence"], extraction)
                self.assertEqual(MemorySession(memory, Model()).chat("我住哪？").recalled, [])

    def test_quoted_retraction_and_authors_own_fact_in_same_turn(self):
        memory = Memory()
        memory.remember(CITY.key, "杭州", expected_revision=0)
        model = Model([update(CITY.key, "", "撤回我的住址", "retract"),
                       update("user.name", "阿梨", "请叫我“阿梨”")])
        turn = MemorySession(memory, model).chat("客户说：「撤回我的住址。」另外，请叫我“阿梨”。")
        self.assertTrue(turn.memory_ok)
        self.assertEqual([item["key"] for item in turn.updates], ["user.name"])
        self.assertEqual(memory.get_fact(CITY.key)["revision"], 1)

    def test_hidden_profile_values_stay_out_of_both_model_calls(self):
        class SelectedMemory(Memory):
            def recall(self, query, *, for_agent, **kwargs):
                assert for_agent
                return {"fragments": [{"fragment": {"id": "city-v1", "content": "CITY_SELECTED"}, "score": 1}]}
        memory, model = SelectedMemory(), Model()
        for field, text, fragment in [(CITY, "CITY_SELECTED", "city-v1"), (COFFEE, "COFFEE_PRIVATE", "coffee-v1")]:
            memory.remember(field.key, text, expected_revision=0)["fragmentId"] = fragment
        MemorySession(memory, model).chat("我住哪？")
        for prompt in model.calls:
            self.assertIn("CITY_SELECTED", json.dumps(prompt))
            self.assertNotIn("COFFEE_PRIVATE", json.dumps(prompt))

    def test_answer_is_never_fact_evidence(self):
        model = Model([update(evidence="ANSWER_FROM_MODEL")])
        memory = Memory()
        turn = MemorySession(memory, model, fields=[CITY]).chat("你猜我住哪里？")
        self.assertEqual(turn.answer, "ANSWER_FROM_MODEL")
        self.assertEqual(turn.errors[0]["stage"], "extraction")
        self.assertNotIn("ANSWER_FROM_MODEL", json.dumps(model.calls[1]))
        self.assertEqual(memory.writes, [])

    def test_validates_whole_batch_before_any_write(self):
        for invalid in [update(key="other.person.city"), update(evidence="不存在的证据"),
                        update(content=""), {**update(), "expected_revision": 999}, update(operation="forget")]:
            with self.subTest(invalid=invalid):
                memory = Memory()
                model = Model([update(COFFEE.key, "用户喜欢拿铁", "喜欢拿铁"), invalid])
                turn = MemorySession(memory, model, fields=[CITY, COFFEE]).chat("搬到上海了，喜欢拿铁")
                self.assertFalse(turn.memory_ok)
                self.assertEqual(memory.writes, [])

    def test_same_field_cannot_be_updated_twice_in_one_proposal(self):
        memory = Memory()
        turn = MemorySession(memory, Model([update(), update()]), fields=[CITY, COFFEE]).chat("搬到上海了")
        self.assertFalse(turn.memory_ok)
        self.assertEqual(memory.writes, [])

    def test_concurrent_update_is_not_overwritten_or_retried(self):
        memory = Memory()
        memory.remember(CITY.key, "北京", expected_revision=0)
        model = Model([update()], on_answer=lambda: memory.remember(CITY.key, "深圳", expected_revision=1))
        turn = MemorySession(memory, model, fields=[CITY]).chat("搬到上海了")
        self.assertEqual(memory.facts[CITY.key]["content"], "深圳")
        self.assertEqual(turn.errors, [{"stage": "commit", "key": CITY.key,
                                       "code": "revision_conflict", "current_revision": 2}])
        self.assertEqual(memory.writes[-1], (CITY.key, 1))

    def test_identical_fact_does_not_create_a_revision(self):
        memory = Memory()
        memory.remember(CITY.key, "用户住上海", expected_revision=0)
        turn = MemorySession(memory, Model([update()]), fields=[CITY]).chat("搬到上海了")
        self.assertEqual(memory.facts[CITY.key]["revision"], 1)
        self.assertEqual(turn.updates, [])

    def test_retraction_stops_recall_and_preserves_revision(self):
        memory = Memory()
        memory.remember(CITY.key, "上海", expected_revision=0)
        turn = MemorySession(memory, Model([update(operation="retract", content="", evidence="住址不对")]),
                             fields=[CITY]).chat("住址不对，先别记住址")
        self.assertEqual(turn.updates[0]["revision"], 2)
        self.assertEqual(memory.recall("住址")["fragments"], [])

    def test_deleted_key_recreated_with_tombstone_revision(self):
        memory = Memory()
        memory.facts[CITY.key] = {"status": "DELETED", "revision": 8, "content": None, "tags": []}
        MemorySession(memory, Model([update()]), fields=[CITY]).chat("搬到上海了")
        self.assertEqual(memory.facts[CITY.key]["revision"], 9)

    def test_ambiguous_write_is_reported_and_not_retried(self):
        memory = Memory()
        memory.failure = VortexConnectionError("timeout")
        turn = MemorySession(memory, Model([update()]), fields=[CITY]).chat("搬到上海了")
        self.assertEqual(turn.errors[0]["code"], "outcome_unknown")
        self.assertEqual(turn.updates, [])
        self.assertEqual(len(memory.writes), 1)

    def test_partial_batch_reports_success_and_failure_separately(self):
        memory = Memory()
        memory.remember(CITY.key, "北京", expected_revision=0)
        model = Model([update(), update(COFFEE.key, "用户喜欢拿铁", "喜欢拿铁")],
                      on_answer=lambda: memory.remember(CITY.key, "深圳", expected_revision=1))
        turn = MemorySession(memory, model, fields=[CITY, COFFEE]).chat("搬到上海了，喜欢拿铁")
        self.assertEqual(len(turn.errors), 1)
        self.assertEqual(turn.updates[0]["key"], COFFEE.key)

    def test_model_failure_never_writes_or_learns(self):
        memory = Memory()
        def fail(messages, **kwargs):
            raise RuntimeError("unavailable")
        with self.assertRaises(RuntimeError):
            MemorySession(memory, fail, fields=[CITY]).chat("搬到上海了")
        self.assertEqual(memory.writes, [])

    def test_empty_extraction_keeps_questions_out_of_memory(self):
        memory = Memory()
        turn = MemorySession(memory, Model(), fields=[CITY]).chat("如果我搬到上海会怎样？")
        self.assertTrue(turn.memory_ok)
        self.assertEqual(memory.writes, [])

    def test_custom_fields_and_existing_expiry_are_preserved(self):
        memory = Memory()
        project = MemoryField("project.language", "编程语言")
        memory.remember(project.key, "Java", expected_revision=0, tags=["project"], expires_at="2099-01-01T00:00:00Z")
        MemorySession(memory, Model([update(project.key, "项目用Python", "改用Python")]), fields=[project]).chat("改用Python")
        self.assertEqual(memory.facts[project.key]["expiresAt"], "2099-01-01T00:00:00Z")
        self.assertIn("project", memory.facts[project.key]["tags"])


if __name__ == "__main__":
    unittest.main()
