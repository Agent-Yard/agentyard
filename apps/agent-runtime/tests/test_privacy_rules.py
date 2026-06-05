import unittest
import os
from unittest.mock import patch

from agentyard_agent_runtime.data_security.mapper import PrivacyMapper
from agentyard_agent_runtime.data_security.rewriter import PrivateLlmRewriter
from agentyard_agent_runtime.data_security.rewriter import RewriteResult
from agentyard_agent_runtime.data_security.rules import restore_value
from agentyard_agent_runtime.data_security.rules import sanitize_value
from agentyard_agent_runtime.data_security.rules import SanitizationResult
from agentyard_agent_runtime.data_security.validator import collect_sensitive_entities
from agentyard_agent_runtime.data_security.validator import PrivacyMappingBlockedError
from agentyard_agent_runtime.data_security.validator import validate_sanitized_output
from agentyard_agent_runtime.privacy_contracts import PrivacyModelBinding, PrivacyPolicy


def _privacy_model_binding(api_key_env_var: str = "AGENTYARD_TEST_PRIVATE_REWRITER_API_KEY") -> PrivacyModelBinding:
    return PrivacyModelBinding(
        resource_id="privacy-resource",
        resource_name="Privacy Model",
        resource_version_id="privacy-resource-version",
        provider_type="OPENAI_COMPATIBLE",
        model_id="privacy-model",
        base_url="https://privacy.example",
        api_key_env_var=api_key_env_var,
        private_deployment=True,
    )


class _FakeMappingEntry:
    def __init__(self, entity_type: str, raw_value: str, ordinal: int, created: bool = True) -> None:
        self.placeholder_id = f"<<{entity_type}_{ordinal:03d}>>"
        self.raw_value = raw_value
        self.entity_type = entity_type
        self.created = created


class _FakePrivacyStore:
    def __init__(self) -> None:
        self.mappings: list[tuple[str, str]] = []
        self.reverse: dict[str, str] = {}
        self.sanitize_records: list[tuple[str, int, dict[str, int]]] = []
        self.blocked_count = 0

    def ensure_mapping(self, entity_type: str, raw_value: str) -> _FakeMappingEntry:
        self.mappings.append((entity_type, raw_value))
        entry = _FakeMappingEntry(entity_type, raw_value, len(self.mappings))
        self.reverse[entry.placeholder_id] = raw_value
        return entry

    def restore_placeholder(self, placeholder_id: str) -> str | None:
        return self.reverse.get(placeholder_id)

    def record_sanitize(self, channel: str, new_placeholder_count: int, entity_type_breakdown: dict[str, int]) -> None:
        self.sanitize_records.append((channel, new_placeholder_count, entity_type_breakdown))

    def record_blocked(self) -> None:
        self.blocked_count += 1


class _FakeTrace:
    def __init__(self) -> None:
        self.sanitize_records: list[tuple[str, dict[str, int], int]] = []
        self.blocked_count = 0

    def record_sanitize(self, channel: str, entity_type_breakdown: dict[str, int], new_placeholder_count: int) -> None:
        self.sanitize_records.append((channel, entity_type_breakdown, new_placeholder_count))

    def record_blocked(self) -> None:
        self.blocked_count += 1


class PrivacyRulesTest(unittest.TestCase):
    def test_private_rewriter_prompt_requires_empty_entities_object(self) -> None:
        rewriter = PrivateLlmRewriter(_privacy_model_binding())

        with patch(
            "agentyard_agent_runtime.data_security.rewriter.chat_completion",
            return_value={"choices": [{"message": {"content": '{"entities":[]}'}}]},
        ) as completion:
            entities = rewriter._extract_entities("hello", "test-key")

        self.assertEqual([], entities)
        system_prompt = completion.call_args.args[1]["messages"][0]["content"]
        self.assertEqual(
            (
                "请从所给的文本中提取可能涉及隐私信息的实体。"
                "只返回一个如下所示的JSON对象: \n"
                '{"entities":[{"rawValue":"...","entityType":"PERSON"}]}\n\n'
                '如果没有隐私信息实体则返回 {"entities":[]}\n'
                "可能的隐私实体类型: PERSON, ACCOUNT, ORDER, PHONE"
            ),
            system_prompt,
        )

    def test_private_rewriter_tolerates_empty_array_or_object_response(self) -> None:
        rewriter = PrivateLlmRewriter(_privacy_model_binding())

        with patch(
            "agentyard_agent_runtime.data_security.rewriter.chat_completion",
            return_value={"choices": [{"message": {"content": "[]"}}]},
        ):
            self.assertEqual([], rewriter._extract_entities("hello", "test-key"))

        with patch(
            "agentyard_agent_runtime.data_security.rewriter.chat_completion",
            return_value={"choices": [{"message": {"content": "{}"}}]},
        ):
            self.assertEqual([], rewriter._extract_entities("hello", "test-key"))

    def test_private_rewriter_replaces_all_occurrences_for_returned_raw_value(self) -> None:
        store = _FakePrivacyStore()
        rewriter = PrivateLlmRewriter(_privacy_model_binding())

        with patch.dict(os.environ, {"AGENTYARD_TEST_PRIVATE_REWRITER_API_KEY": "test-key"}), patch.object(
            rewriter,
            "_extract_entities",
            return_value=[{"rawValue": "Jane Doe", "entityType": "PERSON"}],
        ):
            result = rewriter.rewrite("Jane Doe and Jane Doe", store)

        self.assertEqual("<<PERSON_001>> and <<PERSON_001>>", result.value)
        self.assertEqual({"PERSON": 2}, result.entity_type_breakdown)
        self.assertEqual(1, result.new_placeholder_count)
        self.assertEqual(2, result.total_replacement_count)
        self.assertEqual([("PERSON", "Jane Doe")], store.mappings)

    def test_private_rewriter_should_not_wrap_existing_placeholders(self) -> None:
        store = _FakePrivacyStore()
        rewriter = PrivateLlmRewriter(_privacy_model_binding())

        with patch.dict(os.environ, {"AGENTYARD_TEST_PRIVATE_REWRITER_API_KEY": "test-key"}), patch.object(
            rewriter,
            "_extract_entities",
            return_value=[
                {"rawValue": "xxx-customer-<<PHONE_001>>", "entityType": "ACCOUNT"},
                {"rawValue": "PHONE_001", "entityType": "ACCOUNT"},
                {"rawValue": "xxx-customer-<<PHONE_001>>", "entityType": "USER"},
                {"rawValue": "Jane Doe", "entityType": "PERSON"},
            ],
        ):
            result = rewriter.rewrite("user_id: xxx-customer-<<PHONE_001>> owner Jane Doe", store)

        self.assertEqual("user_id: xxx-customer-<<PHONE_001>> owner <<PERSON_001>>", result.value)
        self.assertEqual({"PERSON": 1}, result.entity_type_breakdown)
        self.assertEqual(1, result.new_placeholder_count)
        self.assertEqual(1, result.total_replacement_count)
        self.assertEqual([("PERSON", "Jane Doe")], store.mappings)

    def test_should_not_replace_values_only_because_key_name_looks_sensitive(self) -> None:
        store = _FakePrivacyStore()

        result = sanitize_value({"customerId": "user-admin", "name": "debug user"}, store)

        self.assertEqual({"customerId": "user-admin", "name": "debug user"}, result.value)
        self.assertEqual({}, result.entity_type_breakdown)
        self.assertEqual(0, result.new_placeholder_count)
        self.assertEqual([], store.mappings)
        self.assertEqual([], collect_sensitive_entities({"customerId": "user-admin", "name": "debug user"}))

    def test_should_not_replace_user_or_order_prefixed_debug_tokens(self) -> None:
        store = _FakePrivacyStore()

        result = sanitize_value("debug ids: user-admin order-preview customer-local", store)

        self.assertEqual("debug ids: user-admin order-preview customer-local", result.value)
        self.assertEqual({}, result.entity_type_breakdown)
        self.assertEqual(0, result.new_placeholder_count)
        self.assertEqual([], store.mappings)
        self.assertEqual([], collect_sensitive_entities("debug ids: user-admin order-preview customer-local"))

    def test_should_not_treat_identifier_numeric_suffix_as_phone(self) -> None:
        store = _FakePrivacyStore()

        result = sanitize_value("user_id: xxx-customer-121381941241", store)

        self.assertEqual("user_id: xxx-customer-121381941241", result.value)
        self.assertEqual({}, result.entity_type_breakdown)
        self.assertEqual(0, result.new_placeholder_count)
        self.assertEqual([], store.mappings)
        self.assertEqual([], collect_sensitive_entities("user_id: xxx-customer-121381941241"))

    def test_should_still_replace_standalone_phone_values(self) -> None:
        store = _FakePrivacyStore()

        result = sanitize_value("phone 13812345678", store)

        self.assertEqual("phone <<PHONE_001>>", result.value)
        self.assertEqual({"PHONE": 1}, result.entity_type_breakdown)
        self.assertEqual(1, result.new_placeholder_count)
        self.assertEqual([("PHONE", "13812345678")], store.mappings)

    def test_should_still_replace_deterministic_email_values(self) -> None:
        store = _FakePrivacyStore()

        result = sanitize_value("email alice@example.com", store)

        self.assertEqual("email <<ACCOUNT_001>>", result.value)
        self.assertEqual({"ACCOUNT": 1}, result.entity_type_breakdown)
        self.assertEqual(1, result.new_placeholder_count)
        self.assertEqual([("ACCOUNT", "alice@example.com")], store.mappings)

    def test_restore_should_resolve_nested_placeholders_from_existing_mappings(self) -> None:
        store = _FakePrivacyStore()
        store.reverse["<<PHONE_001>>"] = "121381941241"
        store.reverse["<<ACCOUNT_001>>"] = "xxx-customer-<<PHONE_001>>"

        restored = restore_value("user_id: <<ACCOUNT_001>>", store)

        self.assertEqual("user_id: xxx-customer-121381941241", restored)

    def test_restore_should_reject_placeholder_nesting_deeper_than_three(self) -> None:
        store = _FakePrivacyStore()
        store.reverse["<<ACCOUNT_001>>"] = "<<ACCOUNT_002>>"
        store.reverse["<<ACCOUNT_002>>"] = "<<ACCOUNT_003>>"
        store.reverse["<<ACCOUNT_003>>"] = "<<ACCOUNT_004>>"
        store.reverse["<<ACCOUNT_004>>"] = "final-value"

        with self.assertRaisesRegex(ValueError, "exceeded nested placeholder depth"):
            restore_value("<<ACCOUNT_001>>", store)

    def test_should_preserve_existing_placeholders_while_sanitizing_raw_values(self) -> None:
        store = _FakePrivacyStore()

        result = sanitize_value("already <<PERSON_001>>, email alice@example.com", store)

        self.assertEqual("already <<PERSON_001>>, email <<ACCOUNT_001>>", result.value)
        self.assertEqual({"ACCOUNT": 1}, result.entity_type_breakdown)
        self.assertEqual(1, result.new_placeholder_count)
        self.assertEqual([("ACCOUNT", "alice@example.com")], store.mappings)
        validate_sanitized_output("already <<PERSON_001>>, email alice@example.com", result.value)

    def test_validator_should_reject_raw_values_even_when_placeholders_exist(self) -> None:
        with self.assertRaises(PrivacyMappingBlockedError):
            validate_sanitized_output(
                "already <<PERSON_001>>, email alice@example.com",
                "already <<PERSON_001>>, email alice@example.com",
            )

    def test_should_sanitize_context_names_with_same_detector_used_by_validator(self) -> None:
        store = _FakePrivacyStore()

        result = sanitize_value("user Alice Johnson and customer 张三", store)
        entities = collect_sensitive_entities("user Alice Johnson and customer 张三")

        self.assertEqual("user <<PERSON_001>> and customer <<PERSON_002>>", result.value)
        self.assertEqual({"PERSON": 2}, result.entity_type_breakdown)
        self.assertEqual(2, result.new_placeholder_count)
        self.assertEqual(2, result.total_replacement_count)
        self.assertEqual([("PERSON", "Alice Johnson"), ("PERSON", "张三")], store.mappings)
        self.assertEqual(["Alice Johnson", "张三"], [entity.raw_value for entity in entities])

    def test_mapper_should_adopt_rewriter_output_when_existing_mapping_replaces_text(self) -> None:
        store = _FakePrivacyStore()
        trace = _FakeTrace()
        mapper = PrivacyMapper(
            PrivacyPolicy(
                session_id="session-1",
                assistant_id="assistant-1",
                agent_id="agent-1",
                enabled=True,
                model_binding=None,
            ),
            store,
            trace,
        )

        with patch(
            "agentyard_agent_runtime.data_security.mapper.sanitize_value",
            return_value=SanitizationResult("customer Alice Johnson", {}, 0, 0),
        ), patch(
            "agentyard_agent_runtime.data_security.mapper.PrivateLlmRewriter.rewrite",
            return_value=RewriteResult("customer <<PERSON_001>>", {"PERSON": 1}, 0, 1),
        ):
            sanitized = mapper.sanitize("customer Alice Johnson", "PROMPT_RUNTIME_MESSAGE")

        self.assertEqual("customer <<PERSON_001>>", sanitized)
        self.assertEqual([("PROMPT_RUNTIME_MESSAGE", 0, {"PERSON": 1})], store.sanitize_records)
        self.assertEqual([("PROMPT_RUNTIME_MESSAGE", {"PERSON": 1}, 0)], trace.sanitize_records)
        self.assertEqual(0, store.blocked_count)
        self.assertEqual(0, trace.blocked_count)

    def test_mapper_should_apply_private_rewriter_to_nested_string_leaves(self) -> None:
        os.environ["TEST_PRIVATE_API_KEY"] = "private-secret"
        self.addCleanup(lambda: os.environ.pop("TEST_PRIVATE_API_KEY", None))
        store = _FakePrivacyStore()
        trace = _FakeTrace()
        mapper = PrivacyMapper(
            PrivacyPolicy(
                session_id="session-1",
                assistant_id="assistant-1",
                agent_id="agent-1",
                enabled=True,
                model_binding=_privacy_model_binding("TEST_PRIVATE_API_KEY"),
            ),
            store,
            trace,
        )

        def rewrite(text: str, _store: _FakePrivacyStore) -> RewriteResult:
            if "Jane Doe" not in text:
                return RewriteResult(text, {}, 0, 0)
            return RewriteResult(text.replace("Jane Doe", "<<PERSON_001>>"), {"PERSON": 1}, 1, 1)

        with patch("agentyard_agent_runtime.data_security.mapper.PrivateLlmRewriter.rewrite", side_effect=rewrite) as rewrite_mock:
            sanitized = mapper.sanitize(
                {"outer": [{"note": "Please help Jane Doe"}], "status": "RECORDED"},
                "TOOL_RESULT",
            )

        self.assertEqual({"outer": [{"note": "Please help <<PERSON_001>>"}], "status": "RECORDED"}, sanitized)
        self.assertEqual(2, rewrite_mock.call_count)
        self.assertEqual(
            [("TOOL_RESULT", 1, {"PERSON": 1})],
            store.sanitize_records,
        )
        self.assertEqual(
            [("TOOL_RESULT", {"PERSON": 1}, 1)],
            trace.sanitize_records,
        )

    def test_mapper_should_merge_rule_and_private_rewriter_replacements(self) -> None:
        os.environ["TEST_PRIVATE_API_KEY"] = "private-secret"
        self.addCleanup(lambda: os.environ.pop("TEST_PRIVATE_API_KEY", None))
        store = _FakePrivacyStore()
        trace = _FakeTrace()
        mapper = PrivacyMapper(
            PrivacyPolicy(
                session_id="session-1",
                assistant_id="assistant-1",
                agent_id="agent-1",
                enabled=True,
                model_binding=_privacy_model_binding("TEST_PRIVATE_API_KEY"),
            ),
            store,
            trace,
        )

        with patch(
            "agentyard_agent_runtime.data_security.mapper.PrivateLlmRewriter.rewrite",
            return_value=RewriteResult(
                "email <<ACCOUNT_001>> and Please help <<PERSON_001>>",
                {"PERSON": 1},
                1,
                1,
            ),
        ) as rewrite:
            sanitized = mapper.sanitize(
                "email alice@example.com and Please help Jane Doe",
                "PROMPT_RUNTIME_MESSAGE",
            )

        self.assertEqual("email <<ACCOUNT_001>> and Please help <<PERSON_001>>", sanitized)
        rewrite.assert_called_once_with("email <<ACCOUNT_001>> and Please help Jane Doe", store)
        self.assertEqual(
            [("PROMPT_RUNTIME_MESSAGE", 2, {"ACCOUNT": 1, "PERSON": 1})],
            store.sanitize_records,
        )
        self.assertEqual(
            [("PROMPT_RUNTIME_MESSAGE", {"ACCOUNT": 1, "PERSON": 1}, 2)],
            trace.sanitize_records,
        )
        self.assertEqual(0, store.blocked_count)
        self.assertEqual(0, trace.blocked_count)
