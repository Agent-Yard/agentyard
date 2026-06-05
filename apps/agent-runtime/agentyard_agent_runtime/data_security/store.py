from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from redis import Redis

from ..privacy_contracts import PRIVACY_CHANNELS, PrivacyMappingTelemetry, PrivacyPolicy
from ..redis_support import RedisSettings, privacy_session_prefix


def _summary_template(policy: PrivacyPolicy) -> dict[str, Any]:
    return {
        "enabled": policy.enabled,
        "privacyModelResourceId": None if policy.model_binding is None else policy.model_binding.resource_id,
        "privacyModelName": None if policy.model_binding is None else policy.model_binding.resource_name,
        "sanitizeCountByChannel": {channel: 0 for channel in PRIVACY_CHANNELS},
        "restoreCountByChannel": {channel: 0 for channel in PRIVACY_CHANNELS},
        "entityTypeBreakdown": {},
        "placeholderCount": 0,
        "unresolvedPlaceholderCount": 0,
        "blockedEventCount": 0,
        "lastProcessedAt": None,
    }


@dataclass(frozen=True)
class MappingEntry:
    placeholder_id: str
    raw_value: str
    entity_type: str
    created: bool


class SessionPrivacyMapStore:
    _ENSURE_MAPPING_SCRIPT = """
local forwardKey = KEYS[1]
local reverseKey = KEYS[2]
local summaryKey = KEYS[3]

local fingerprint = ARGV[1]
local entityType = ARGV[2]
local reversePayload = ARGV[3]
local timestamp = ARGV[4]
local enabled = ARGV[5] == "1"
local privacyModelResourceId = ARGV[6]
local privacyModelName = ARGV[7]

local summaryRaw = redis.call("GET", summaryKey)
local summary
if summaryRaw and summaryRaw ~= "" then
    summary = cjson.decode(summaryRaw)
else
    summary = {
        enabled = enabled,
        privacyModelResourceId = privacyModelResourceId ~= "" and privacyModelResourceId or cjson.null,
        privacyModelName = privacyModelName ~= "" and privacyModelName or cjson.null,
        sanitizeCountByChannel = {},
        restoreCountByChannel = {},
        entityTypeBreakdown = {},
        placeholderCount = 0,
        unresolvedPlaceholderCount = 0,
        blockedEventCount = 0,
        lastProcessedAt = cjson.null
    }
end

local existingPlaceholder = redis.call("HGET", forwardKey, fingerprint)
local created = "0"

if existingPlaceholder then
    local existingReversePayload = redis.call("HGET", reverseKey, existingPlaceholder)
    if not existingReversePayload then
        return redis.error_reply("privacy reverse mapping missing for placeholder " .. existingPlaceholder)
    end
    local reverseEntry = cjson.decode(existingReversePayload)
    reverseEntry["lastSeenAt"] = timestamp
    local encodedReverseEntry = cjson.encode(reverseEntry)
    redis.call("HSET", reverseKey, existingPlaceholder, encodedReverseEntry)
    return {existingPlaceholder, encodedReverseEntry, created}
end

local breakdown = summary["entityTypeBreakdown"] or {}
local nextOrdinal = tonumber(breakdown[entityType] or 0) + 1
local placeholderId = string.format("<<%s_%03d>>", entityType, nextOrdinal)

redis.call("HSET", forwardKey, fingerprint, placeholderId)
redis.call("HSET", reverseKey, placeholderId, reversePayload)

breakdown[entityType] = nextOrdinal
summary["entityTypeBreakdown"] = breakdown
summary["placeholderCount"] = tonumber(summary["placeholderCount"] or 0) + 1
redis.call("SET", summaryKey, cjson.encode(summary))

created = "1"
return {placeholderId, reversePayload, created}
"""

    def __init__(self, policy: PrivacyPolicy, redis_client: Redis | None = None) -> None:
        settings = RedisSettings.from_env()
        self._redis = redis_client or Redis.from_url(
            settings.url,
            decode_responses=True,
            encoding="utf-8",
            socket_connect_timeout=3,
            socket_timeout=3,
        )
        prefix = privacy_session_prefix()
        self._session_prefix = f"{prefix}:{policy.session_id}"
        self._policy = policy
        secret = (os.getenv("AGENTYARD_PRIVACY_SESSION_STORE_ENCRYPTION_KEY") or "").strip()
        if policy.enabled and not secret:
            raise RuntimeError("AGENTYARD_PRIVACY_SESSION_STORE_ENCRYPTION_KEY is required when privacy mapping is enabled")
        self._fingerprint_secret = hashlib.sha256((secret + ":fingerprint").encode("utf-8")).digest()
        self._cipher = AESGCM(hashlib.sha256(secret.encode("utf-8")).digest()) if secret else None
        self._ensure_summary(policy)
        self._ensure_mapping_script = self._redis.register_script(self._ENSURE_MAPPING_SCRIPT)

    def _ensure_summary(self, policy: PrivacyPolicy) -> None:
        summary_key = self.summary_key
        if self._redis.exists(summary_key):
            return
        self._redis.set(summary_key, json.dumps(_summary_template(policy), ensure_ascii=False))

    @property
    def forward_key(self) -> str:
        return f"{self._session_prefix}:forward"

    @property
    def reverse_key(self) -> str:
        return f"{self._session_prefix}:reverse"

    @property
    def fragments_key(self) -> str:
        return f"{self._session_prefix}:fragments"

    @property
    def summary_key(self) -> str:
        return f"{self._session_prefix}:summary"

    def close(self) -> None:
        self._redis.close()

    def ensure_mapping(self, entity_type: str, raw_value: str) -> MappingEntry:
        normalized = raw_value.strip()
        fingerprint = self._fingerprint(entity_type, normalized)
        timestamp = self._now()
        reverse_entry = {
            "ciphertext": self._encrypt(normalized),
            "entityType": entity_type,
            "firstSeenAt": timestamp,
            "lastSeenAt": timestamp,
        }
        placeholder_id, reverse_payload, created_flag = self._ensure_mapping_script(
            keys=[self.forward_key, self.reverse_key, self.summary_key],
            args=[
                fingerprint,
                entity_type,
                json.dumps(reverse_entry, ensure_ascii=False),
                timestamp,
                "1" if self._policy.enabled else "0",
                "" if self._policy.model_binding is None else self._policy.model_binding.resource_id,
                "" if self._policy.model_binding is None else self._policy.model_binding.resource_name,
            ],
        )
        entry = json.loads(str(reverse_payload))
        return MappingEntry(
            str(placeholder_id),
            self._decrypt(entry["ciphertext"]),
            str(entry["entityType"]),
            str(created_flag) == "1",
        )

    def restore_placeholder(self, placeholder_id: str) -> str | None:
        payload = self._redis.hget(self.reverse_key, placeholder_id)
        if payload is None:
            return None
        entry = json.loads(payload)
        self._touch_reverse(placeholder_id, entry)
        return self._decrypt(entry["ciphertext"])

    def read_sanitized_fragment(self, cache_key: str) -> Any | None:
        payload = self._redis.hget(self.fragments_key, cache_key)
        if payload is None:
            return None
        entry = json.loads(payload)
        return json.loads(self._decrypt(entry["ciphertext"]))

    def write_sanitized_fragment(self, cache_key: str, value: Any, metadata: dict[str, Any]) -> None:
        entry = {
            "ciphertext": self._encrypt(json.dumps(value, ensure_ascii=False)),
            "metadata": {
                **metadata,
                "cachedAt": self._now(),
            },
        }
        self._redis.hset(self.fragments_key, cache_key, json.dumps(entry, ensure_ascii=False))

    def fingerprint_fragment_content(self, payload: bytes) -> str:
        return hmac.new(self._fingerprint_secret, b"fragment:" + payload, hashlib.sha256).hexdigest()

    def record_sanitize(self, channel: str, new_placeholder_count: int, entity_type_breakdown: dict[str, int]) -> None:
        def mutate(summary: dict[str, Any]) -> None:
            counts = summary.setdefault("sanitizeCountByChannel", {})
            counts[channel] = int(counts.get(channel, 0)) + 1
            summary["lastProcessedAt"] = self._now()

        self._mutate_summary(mutate)

    def record_restore(self, channel: str, unresolved_count: int) -> None:
        def mutate(summary: dict[str, Any]) -> None:
            counts = summary.setdefault("restoreCountByChannel", {})
            counts[channel] = int(counts.get(channel, 0)) + 1
            summary["unresolvedPlaceholderCount"] = int(summary.get("unresolvedPlaceholderCount", 0)) + max(unresolved_count, 0)
            summary["lastProcessedAt"] = self._now()

        self._mutate_summary(mutate)

    def record_blocked(self) -> None:
        self._mutate_summary(
            lambda summary: (
                summary.__setitem__("blockedEventCount", int(summary.get("blockedEventCount", 0)) + 1),
                summary.__setitem__("lastProcessedAt", self._now()),
            )
        )

    def summary_telemetry(self) -> PrivacyMappingTelemetry:
        summary = self.read_summary()
        return PrivacyMappingTelemetry(
            enabled=bool(summary.get("enabled", False)),
            privacyModelResourceId=summary.get("privacyModelResourceId"),
            privacyModelResourceName=summary.get("privacyModelName"),
            sanitizeCountByChannel=dict(summary.get("sanitizeCountByChannel") or {}),
            restoreCountByChannel=dict(summary.get("restoreCountByChannel") or {}),
            entityTypeBreakdown=dict(summary.get("entityTypeBreakdown") or {}),
            placeholderCount=int(summary.get("placeholderCount", 0)),
            unresolvedPlaceholderCount=int(summary.get("unresolvedPlaceholderCount", 0)),
            blockedEventCount=int(summary.get("blockedEventCount", 0)),
            lastProcessedAt=summary.get("lastProcessedAt"),
        )

    def read_summary(self) -> dict[str, Any]:
        payload = self._redis.get(self.summary_key)
        return {} if payload is None else json.loads(payload)

    def _mutate_summary(self, mutator) -> None:
        summary = self.read_summary()
        if not summary:
            return
        mutator(summary)
        self._redis.set(self.summary_key, json.dumps(summary, ensure_ascii=False))

    def _reverse_entry(self, placeholder_id: str) -> dict[str, Any]:
        payload = self._redis.hget(self.reverse_key, placeholder_id)
        if payload is None:
            raise ValueError(f"unknown placeholder: {placeholder_id}")
        entry = json.loads(payload)
        entry["rawValue"] = self._decrypt(entry["ciphertext"])
        return entry

    def _touch_reverse(self, placeholder_id: str, entry: dict[str, Any]) -> None:
        updated = dict(entry)
        updated.pop("rawValue", None)
        updated["lastSeenAt"] = self._now()
        self._redis.hset(self.reverse_key, placeholder_id, json.dumps(updated, ensure_ascii=False))

    def _fingerprint(self, entity_type: str, raw_value: str) -> str:
        digest = hmac.new(
            self._fingerprint_secret,
            f"{entity_type}:{raw_value}".encode("utf-8"),
            hashlib.sha256,
        ).hexdigest()
        return digest

    def _encrypt(self, raw_value: str) -> str:
        if self._cipher is None:
            raise RuntimeError("privacy encryption key is not configured")
        nonce = os.urandom(12)
        ciphertext = self._cipher.encrypt(nonce, raw_value.encode("utf-8"), None)
        return base64.b64encode(nonce + ciphertext).decode("ascii")

    def _decrypt(self, encoded: str) -> str:
        if self._cipher is None:
            raise RuntimeError("privacy encryption key is not configured")
        payload = base64.b64decode(encoded.encode("ascii"))
        nonce = payload[:12]
        ciphertext = payload[12:]
        return self._cipher.decrypt(nonce, ciphertext, None).decode("utf-8")

    def _now(self) -> str:
        return datetime.now(UTC).isoformat()
