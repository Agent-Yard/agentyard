import unittest

from lynxus_agent_runtime.data_security.rules import sanitize_value
from lynxus_agent_runtime.data_security.validator import collect_sensitive_entities


class _FakeMappingEntry:
    def __init__(self, entity_type: str, raw_value: str, ordinal: int) -> None:
        self.placeholder_id = f"[{entity_type}_{ordinal:03d}]"
        self.raw_value = raw_value
        self.entity_type = entity_type
        self.created = True


class _FakePrivacyStore:
    def __init__(self) -> None:
        self.mappings: list[tuple[str, str]] = []

    def ensure_mapping(self, entity_type: str, raw_value: str) -> _FakeMappingEntry:
        self.mappings.append((entity_type, raw_value))
        return _FakeMappingEntry(entity_type, raw_value, len(self.mappings))


class PrivacyRulesTest(unittest.TestCase):
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

    def test_should_still_replace_deterministic_email_values(self) -> None:
        store = _FakePrivacyStore()

        result = sanitize_value("email alice@example.com", store)

        self.assertEqual("email [ACCOUNT_001]", result.value)
        self.assertEqual({"ACCOUNT": 1}, result.entity_type_breakdown)
        self.assertEqual(1, result.new_placeholder_count)
        self.assertEqual([("ACCOUNT", "alice@example.com")], store.mappings)
