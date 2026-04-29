from __future__ import annotations

from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

import pytest

from lynxus_extension_sdk.common.canonical_json import (
    CanonicalJsonError,
    _parse_json_text,
    canonical_bytes,
    sha256_digest,
)
from lynxus_extension_sdk.common.definition_digest import (
    channel_provider_definition_digest,
    channel_provider_definition_digest_input,
    tool_connector_definition_digest,
    tool_connector_definition_digest_input,
)


REPO_ROOT = Path(__file__).resolve().parents[3]
FIXTURES_DIR = REPO_ROOT / "packages/extension-protocol/contract-tests/fixtures/canonical-json"


@pytest.mark.parametrize("fixture_path", sorted(FIXTURES_DIR.glob("*.json")), ids=lambda path: path.name)
def test_canonical_json_fixtures_match_protocol_contract(fixture_path: Path) -> None:
    fixture = _parse_json_text(fixture_path.read_text(encoding="utf-8"))
    expected_error_code = fixture["expectedErrorCode"]

    try:
        value = _fixture_value(fixture)
        if expected_error_code is not None:
            pytest.fail(f"{fixture_path} expected {expected_error_code}, got valid canonical JSON")
        assert canonical_bytes(value).hex() == fixture["expectedCanonicalUtf8Hex"]
        assert sha256_digest(value) == fixture["expectedDigest"]
        _assert_descriptor_digest_helper(fixture)
    except CanonicalJsonError as error:
        if expected_error_code is None:
            pytest.fail(f"{fixture_path} unexpected canonical JSON error {error.code}")
        assert error.code == expected_error_code


def _assert_descriptor_digest_helper(fixture: dict[str, Any]) -> None:
    fixture_type = fixture.get("type")
    if fixture_type == "channelProviderDefinitionDigest":
        assert channel_provider_definition_digest(fixture["input"]) == fixture["expectedDigest"]
    if fixture_type == "toolConnectorDefinitionDigest":
        assert tool_connector_definition_digest(fixture["input"]) == fixture["expectedDigest"]


def _fixture_value(fixture: dict[str, Any]) -> Any:
    fixture_type = fixture.get("type")
    if fixture_type == "registrationConfigDigest":
        return _normalize_registration_config(fixture["input"])
    if fixture_type == "channelProviderDefinitionDigest":
        return channel_provider_definition_digest_input(fixture["input"])
    if fixture_type == "toolConnectorDefinitionDigest":
        return tool_connector_definition_digest_input(fixture["input"])
    return _parse_json_text(fixture["input"])


def _normalize_registration_config(input_value: dict[str, Any]) -> dict[str, Any]:
    services = []
    for service in input_value.get("services", []):
        exposes = service.get("exposes") or {}
        auth = service.get("auth") or {}
        services.append(
            {
                "registrationId": service.get("registrationId"),
                "source": service.get("source"),
                "baseUrl": _normalize_base_url(service.get("baseUrl")),
                "exposes": {
                    "channelProviderTypes": sorted(exposes.get("channelProviderTypes") or [], key=_utf16_sort_key),
                    "toolConnectorTypes": sorted(exposes.get("toolConnectorTypes") or [], key=_utf16_sort_key),
                },
                "auth": {
                    "type": auth.get("type"),
                },
            }
        )
    services.sort(key=lambda service: _utf16_sort_key(service["registrationId"]))
    return {"services": services}


def _normalize_base_url(value: str) -> str:
    parsed = urlsplit(value)
    scheme = parsed.scheme.lower()
    if scheme not in {"http", "https"} or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("REGISTRATION_CONFIG_INVALID")
    if parsed.hostname is None:
        raise ValueError("REGISTRATION_CONFIG_INVALID")
    port = parsed.port
    is_default_port = (scheme == "http" and port == 80) or (scheme == "https" and port == 443)
    authority = parsed.hostname.lower() if port is None or is_default_port else f"{parsed.hostname.lower()}:{port}"
    path = "" if parsed.path in {"", "/"} else parsed.path.rstrip("/")
    return f"{scheme}://{authority}{path}"


def _utf16_sort_key(value: str) -> bytes:
    return value.encode("utf-16-be")
