import os
import unittest
from collections import deque
from unittest.mock import patch

from fastapi.testclient import TestClient

os.environ.setdefault("LYNXUS_INTERNAL_AUTH_TOKEN", "test-internal-token")

from lynxus_agent_runtime.main import app


class FakeRedisClient:
    def __init__(self, ping_results: list[object]) -> None:
        self._ping_results = deque(ping_results)

    async def ping(self) -> object:
        if not self._ping_results:
            raise AssertionError("unexpected redis ping")
        result = self._ping_results.popleft()
        if isinstance(result, Exception):
            raise result
        return result

    async def aclose(self) -> None:
        return None


class FakeTranscriptStore:
    settings = type(
        "Settings",
        (),
        {
            "database_url": "postgresql+psycopg://test",
            "turn_execution_retention_seconds": 60,
            "transcript_entry_retention_seconds": 60,
            "retention_sweep_limit": 50,
            "transcript_cache_ttl_seconds": 60,
        },
    )()

    def __init__(self, check_results: list[object]) -> None:
        self._check_results = deque(check_results)

    def initialize(self) -> None:
        return None

    def sweep_expired(self) -> dict[str, int]:
        return {
            "turnExecutionsAborted": 0,
            "turnExecutionsDeleted": 0,
            "transcriptEntriesAborted": 0,
            "transcriptEntriesDeleted": 0,
        }

    def check_database(self) -> dict[str, object]:
        if not self._check_results:
            raise AssertionError("unexpected database check")
        result = self._check_results.popleft()
        if isinstance(result, Exception):
            raise result
        return result

    def close(self) -> None:
        return None


class AgentRuntimeHealthTest(unittest.TestCase):
    def test_should_return_up_when_redis_and_database_are_ready(self) -> None:
        fake_redis_client = FakeRedisClient([True, True])
        fake_transcript_store = FakeTranscriptStore([{"backend": "postgresql", "schema": "public"}])

        with patch("lynxus_agent_runtime.main.create_redis_client", return_value=fake_redis_client):
            with patch("lynxus_agent_runtime.main.create_transcript_store", return_value=fake_transcript_store):
                with TestClient(app) as client:
                    response = client.get("/healthz")

        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertEqual(payload["status"], "UP")
        self.assertEqual(payload["service"], "lynxus-agent-runtime")
        self.assertEqual(payload["dependencies"]["redis"]["status"], "UP")
        self.assertEqual(payload["dependencies"]["redis"]["host"], "127.0.0.1")
        self.assertEqual(payload["dependencies"]["database"]["status"], "UP")
        self.assertEqual(payload["dependencies"]["database"]["schema"], "public")

    def test_should_return_down_and_503_when_redis_probe_raises(self) -> None:
        fake_redis_client = FakeRedisClient([True, RuntimeError("redis unavailable")])
        fake_transcript_store = FakeTranscriptStore([{"backend": "postgresql", "schema": "public"}])

        with patch("lynxus_agent_runtime.main.create_redis_client", return_value=fake_redis_client):
            with patch("lynxus_agent_runtime.main.create_transcript_store", return_value=fake_transcript_store):
                with TestClient(app) as client:
                    response = client.get("/healthz")

        self.assertEqual(response.status_code, 503)
        payload = response.json()
        self.assertEqual(payload["status"], "DOWN")
        self.assertEqual(payload["dependencies"]["redis"]["status"], "DOWN")
        self.assertEqual(payload["dependencies"]["redis"]["detail"], "redis unavailable")
        self.assertEqual(payload["dependencies"]["database"]["status"], "UP")

    def test_should_return_down_and_503_when_database_probe_raises(self) -> None:
        fake_redis_client = FakeRedisClient([True, True])
        fake_transcript_store = FakeTranscriptStore([RuntimeError("postgres unavailable")])

        with patch("lynxus_agent_runtime.main.create_redis_client", return_value=fake_redis_client):
            with patch("lynxus_agent_runtime.main.create_transcript_store", return_value=fake_transcript_store):
                with TestClient(app) as client:
                    response = client.get("/healthz")

        self.assertEqual(response.status_code, 503)
        payload = response.json()
        self.assertEqual(payload["status"], "DOWN")
        self.assertEqual(payload["dependencies"]["redis"]["status"], "UP")
        self.assertEqual(payload["dependencies"]["database"]["status"], "DOWN")
        self.assertEqual(payload["dependencies"]["database"]["detail"], "postgres unavailable")

    def test_should_return_down_when_owner_context_sequence_table_is_unreadable(self) -> None:
        fake_redis_client = FakeRedisClient([True, True])
        fake_transcript_store = FakeTranscriptStore(
            [RuntimeError("relation owner_context_sequence does not exist")]
        )

        with patch("lynxus_agent_runtime.main.create_redis_client", return_value=fake_redis_client):
            with patch("lynxus_agent_runtime.main.create_transcript_store", return_value=fake_transcript_store):
                with TestClient(app) as client:
                    response = client.get("/healthz")

        self.assertEqual(response.status_code, 503)
        payload = response.json()
        self.assertEqual(payload["status"], "DOWN")
        self.assertEqual(payload["dependencies"]["redis"]["status"], "UP")
        self.assertEqual(payload["dependencies"]["database"]["status"], "DOWN")
        self.assertIn("owner_context_sequence", payload["dependencies"]["database"]["detail"])
