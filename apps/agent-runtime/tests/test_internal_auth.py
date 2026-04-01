import os
import unittest

from fastapi.testclient import TestClient

os.environ.setdefault("LYNXUS_INTERNAL_AUTH_TOKEN", "test-internal-token")

from app.main import app


class AgentRuntimeInternalAuthTest(unittest.TestCase):
    def test_should_reject_missing_internal_token(self) -> None:
        with TestClient(app) as client:
            response = client.post("/agent-runs/start", json={})

        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json()["detail"], "internal authentication is required")

    def test_should_reject_invalid_internal_token(self) -> None:
        with TestClient(app) as client:
            response = client.post(
                "/agent-runs/start",
                json={},
                headers={"Authorization": "Bearer wrong-token"},
            )

        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json()["detail"], "invalid internal authentication token")

    def test_should_allow_request_to_reach_validation_when_internal_token_is_valid(self) -> None:
        with TestClient(app) as client:
            response = client.post(
                "/agent-runs/start",
                json={},
                headers={"Authorization": "Bearer test-internal-token"},
            )

        self.assertEqual(response.status_code, 422)

    def test_should_return_traceparent_header_for_valid_internal_request(self) -> None:
        with TestClient(app) as client:
            response = client.post(
                "/agent-runs/start",
                json={},
                headers={
                    "Authorization": "Bearer test-internal-token",
                    "traceparent": "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                },
            )

        self.assertEqual(response.status_code, 422)
        self.assertTrue(response.headers["traceparent"].startswith("00-0123456789abcdef0123456789abcdef-"))
