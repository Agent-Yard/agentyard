import os
import unittest
from contextlib import contextmanager
from unittest.mock import patch

from fastapi.testclient import TestClient

os.environ.setdefault("LYNXUS_INTERNAL_AUTH_TOKEN", "test-internal-token")

from lynxus_agent_runtime.main import app


class FakeRedisClient:
    async def ping(self) -> bool:
        return True

    async def aclose(self) -> None:
        return None


@contextmanager
def agent_runtime_client():
    with patch("lynxus_agent_runtime.main.create_redis_client", return_value=FakeRedisClient()):
        with TestClient(app) as client:
            yield client


class AgentRuntimeInternalAuthTest(unittest.TestCase):
    def test_should_reject_missing_internal_token(self) -> None:
        with agent_runtime_client() as client:
            response = client.post("/agent-turns/execute-stream", json={})

        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json()["detail"], "internal authentication is required")

    def test_should_reject_invalid_internal_token(self) -> None:
        with agent_runtime_client() as client:
            response = client.post(
                "/agent-turns/execute-stream",
                json={},
                headers={"Authorization": "Bearer wrong-token"},
            )

        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json()["detail"], "invalid internal authentication token")

    def test_should_allow_request_to_reach_validation_when_internal_token_is_valid(self) -> None:
        with agent_runtime_client() as client:
            response = client.post(
                "/agent-turns/execute-stream",
                json={},
                headers={"Authorization": "Bearer test-internal-token"},
            )

        self.assertEqual(response.status_code, 422)

    def test_should_not_register_legacy_agent_turn_execute_endpoint(self) -> None:
        with agent_runtime_client() as client:
            response = client.post(
                "/agent-turns/execute",
                json={},
                headers={"Authorization": "Bearer test-internal-token"},
            )

        self.assertEqual(response.status_code, 404)

    def test_should_protect_extension_registry_validation_endpoint(self) -> None:
        with agent_runtime_client() as client:
            missing = client.get("/internal/extension-registry/tool-connectors/validation")
            invalid = client.get(
                "/internal/extension-registry/tool-connectors/validation",
                headers={"Authorization": "Bearer wrong-token"},
            )
            valid = client.get(
                "/internal/extension-registry/tool-connectors/validation",
                headers={"Authorization": "Bearer test-internal-token"},
            )

        self.assertEqual(missing.status_code, 401)
        self.assertEqual(invalid.status_code, 401)
        self.assertEqual(valid.status_code, 200)
        self.assertEqual(valid.json()["status"], "READY")

    def test_should_return_traceparent_header_for_valid_internal_request(self) -> None:
        with agent_runtime_client() as client:
            response = client.post(
                "/agent-turns/execute-stream",
                json={},
                headers={
                    "Authorization": "Bearer test-internal-token",
                    "traceparent": "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                },
            )

        self.assertEqual(response.status_code, 422)
        self.assertTrue(response.headers["traceparent"].startswith("00-0123456789abcdef0123456789abcdef-"))
