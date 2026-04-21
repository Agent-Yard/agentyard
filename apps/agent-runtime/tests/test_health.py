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


class AgentRuntimeHealthTest(unittest.TestCase):
    def test_should_return_up_when_redis_is_ready(self) -> None:
        fake_redis_client = FakeRedisClient([True, True])

        with patch("lynxus_agent_runtime.main.create_redis_client", return_value=fake_redis_client):
            with TestClient(app) as client:
                response = client.get("/healthz")

        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertEqual(payload["status"], "UP")
        self.assertEqual(payload["service"], "lynxus-agent-runtime")
        self.assertEqual(payload["dependencies"]["redis"]["status"], "UP")
        self.assertEqual(payload["dependencies"]["redis"]["host"], "127.0.0.1")

    def test_should_return_down_and_503_when_redis_probe_raises(self) -> None:
        fake_redis_client = FakeRedisClient([True, RuntimeError("redis unavailable")])

        with patch("lynxus_agent_runtime.main.create_redis_client", return_value=fake_redis_client):
            with TestClient(app) as client:
                response = client.get("/healthz")

        self.assertEqual(response.status_code, 503)
        payload = response.json()
        self.assertEqual(payload["status"], "DOWN")
        self.assertEqual(payload["dependencies"]["redis"]["status"], "DOWN")
        self.assertEqual(payload["dependencies"]["redis"]["detail"], "redis unavailable")
