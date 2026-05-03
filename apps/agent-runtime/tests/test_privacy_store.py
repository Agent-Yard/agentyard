import hashlib
import json
import os
import unittest

from lynxus_agent_runtime.data_security.store import SessionPrivacyMapStore
from lynxus_agent_runtime.privacy_contracts import PrivacyPolicy, PrivacyStrategy
from lynxus_agent_runtime.privacy_pipeline import PrivacyPipeline


class _FakeRedis:
    def __init__(self) -> None:
        self._strings: dict[str, str] = {}
        self._hashes: dict[str, dict[str, str]] = {}

    def register_script(self, _script: str):
        def run(*, keys, args):
            forward_key, reverse_key, summary_key = keys
            fingerprint, entity_type, reverse_payload, timestamp, enabled, privacy_model_resource_id, privacy_model_name = args

            summary_raw = self._strings.get(summary_key)
            if summary_raw:
                summary = json.loads(summary_raw)
            else:
                summary = {
                    "enabled": enabled == "1",
                    "privacyModelResourceId": privacy_model_resource_id or None,
                    "privacyModelName": privacy_model_name or None,
                    "sanitizeCountByChannel": {},
                    "restoreCountByChannel": {},
                    "entityTypeBreakdown": {},
                    "placeholderCount": 0,
                    "unresolvedPlaceholderCount": 0,
                    "blockedEventCount": 0,
                    "lastProcessedAt": None,
                }

            forward_hash = self._hashes.setdefault(forward_key, {})
            reverse_hash = self._hashes.setdefault(reverse_key, {})
            existing_placeholder = forward_hash.get(fingerprint)
            if existing_placeholder:
                existing_reverse_payload = reverse_hash[existing_placeholder]
                reverse_entry = json.loads(existing_reverse_payload)
                reverse_entry["lastSeenAt"] = timestamp
                encoded = json.dumps(reverse_entry, ensure_ascii=False)
                reverse_hash[existing_placeholder] = encoded
                return [existing_placeholder, encoded, "0"]

            next_ordinal = int(summary["entityTypeBreakdown"].get(entity_type, 0)) + 1
            placeholder_id = f"[{entity_type}_{next_ordinal:03d}]"
            forward_hash[fingerprint] = placeholder_id
            reverse_hash[placeholder_id] = reverse_payload
            summary["entityTypeBreakdown"][entity_type] = next_ordinal
            summary["placeholderCount"] = int(summary.get("placeholderCount", 0)) + 1
            self._strings[summary_key] = json.dumps(summary, ensure_ascii=False)
            return [placeholder_id, reverse_payload, "1"]

        return run

    def exists(self, key: str) -> int:
        return int(key in self._strings)

    def set(self, key: str, value: str) -> None:
        self._strings[key] = value

    def get(self, key: str) -> str | None:
        return self._strings.get(key)

    def hget(self, key: str, field: str) -> str | None:
        return self._hashes.get(key, {}).get(field)

    def hset(self, key: str, field: str, value: str) -> None:
        self._hashes.setdefault(key, {})[field] = value

    def close(self) -> None:
        return None


class SessionPrivacyMapStoreTest(unittest.TestCase):
    def setUp(self) -> None:
        os.environ["LYNXUS_PRIVACY_SESSION_STORE_ENCRYPTION_KEY"] = "test-secret-key"
        self.policy = PrivacyPolicy(
            session_id="session-1",
            assistant_id="assistant-1",
            agent_id="agent-1",
            enabled=True,
            model_binding=None,
        )

    def tearDown(self) -> None:
        os.environ.pop("LYNXUS_PRIVACY_SESSION_STORE_ENCRYPTION_KEY", None)

    def test_should_reuse_existing_mapping_for_same_value(self) -> None:
        store = SessionPrivacyMapStore(self.policy, redis_client=_FakeRedis())

        first = store.ensure_mapping("PERSON", "Alice Johnson")
        second = store.ensure_mapping("PERSON", "Alice Johnson")
        summary = store.read_summary()

        self.assertEqual(first.placeholder_id, "[PERSON_001]")
        self.assertTrue(first.created)
        self.assertEqual(second.placeholder_id, "[PERSON_001]")
        self.assertFalse(second.created)
        self.assertEqual(summary["placeholderCount"], 1)
        self.assertEqual(summary["entityTypeBreakdown"]["PERSON"], 1)

    def test_should_allocate_incrementing_placeholders_per_entity_type(self) -> None:
        store = SessionPrivacyMapStore(self.policy, redis_client=_FakeRedis())

        first = store.ensure_mapping("PERSON", "Alice Johnson")
        second = store.ensure_mapping("PERSON", "Bob Stone")
        third = store.ensure_mapping("ACCOUNT", "alice@example.com")
        summary = store.read_summary()

        self.assertEqual(first.placeholder_id, "[PERSON_001]")
        self.assertEqual(second.placeholder_id, "[PERSON_002]")
        self.assertEqual(third.placeholder_id, "[ACCOUNT_001]")
        self.assertEqual(summary["placeholderCount"], 3)
        self.assertEqual(summary["entityTypeBreakdown"], {"PERSON": 2, "ACCOUNT": 1})

    def test_should_store_sanitized_fragments_in_session_hash(self) -> None:
        redis_client = _FakeRedis()
        store = SessionPrivacyMapStore(self.policy, redis_client=redis_client)

        store.write_sanitized_fragment(
            "fragment:key-1",
            "hello [PERSON_001]",
            {
                "policyVersion": "agent-runtime-privacy-v2",
                "strategy": "RULES_THEN_PRIVATE_LLM",
                "source": "recent_message:msg-1:1",
                "contentHash": "hash-1",
            },
        )

        self.assertEqual("hello [PERSON_001]", store.read_sanitized_fragment("fragment:key-1"))
        self.assertIsNone(store.read_sanitized_fragment("fragment:missing"))
        raw_entry = redis_client._hashes[store.fragments_key]["fragment:key-1"]
        self.assertNotIn("hello [PERSON_001]", raw_entry)
        self.assertNotIn("value", json.loads(raw_entry))
        self.assertIn("ciphertext", json.loads(raw_entry))
        self.assertEqual("recent_message:msg-1:1", json.loads(raw_entry)["metadata"]["source"])

    def test_fragment_cache_metadata_should_use_keyed_content_fingerprint(self) -> None:
        store = SessionPrivacyMapStore(self.policy, redis_client=_FakeRedis())
        pipeline = PrivacyPipeline.__new__(PrivacyPipeline)
        pipeline._policy = self.policy
        pipeline._store = store

        metadata = pipeline._fragment_cache_metadata(
            PrivacyStrategy.RULES_THEN_PRIVATE_LLM,
            "tool_result:create_ticket",
            {"note": "hi"},
        )

        self.assertNotIn("contentHash", metadata)
        self.assertIn("contentFingerprint", metadata)
        raw_sha = hashlib.sha256(json.dumps({"note": "hi"}, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()
        self.assertNotEqual(raw_sha, metadata["contentFingerprint"])
