from __future__ import annotations

from pathlib import Path

import pytest

from lynxus_agent_runtime import extension_protocol as runtime_protocol
from lynxus_extension_sdk.common import CanonicalJsonError
from lynxus_extension_sdk.common import canonical_bytes as sdk_canonical_bytes
from lynxus_extension_sdk.common import canonicalize as sdk_canonicalize
from lynxus_extension_sdk.common import sha256_digest as sdk_sha256_digest
from lynxus_extension_sdk.protocol import (
    ExtensionErrorCategory,
    ExtensionErrorParseError,
)
from lynxus_extension_sdk.protocol import parse_extension_error_json as sdk_parse_extension_error_json
from lynxus_extension_sdk.protocol import parse_non_2xx_extension_error as sdk_parse_non_2xx_extension_error
from lynxus_extension_sdk.testing import default_protocol_fixtures, load_json_fixture
from lynxus_extension_sdk.validation import validate_manifest_object as sdk_validate_manifest_object


FIXTURES = default_protocol_fixtures(Path(__file__))


def test_runtime_protocol_adapter_reexports_sdk_protocol_functions() -> None:
    assert runtime_protocol.canonicalize is sdk_canonicalize
    assert runtime_protocol.canonical_bytes is sdk_canonical_bytes
    assert runtime_protocol.sha256_digest is sdk_sha256_digest
    assert runtime_protocol.parse_extension_error_json is sdk_parse_extension_error_json
    assert runtime_protocol.parse_non_2xx_extension_error is sdk_parse_non_2xx_extension_error
    assert runtime_protocol.validate_manifest_object is sdk_validate_manifest_object


def test_runtime_canonical_json_uses_shared_sdk_fixture_behavior() -> None:
    valid_fixture = load_json_fixture(FIXTURES.canonical_json_dir / "string-escape-unicode.json")

    assert runtime_protocol.canonical_bytes(valid_fixture["input"]).hex() == valid_fixture["expectedCanonicalUtf8Hex"]
    assert runtime_protocol.sha256_digest(valid_fixture["input"]) == valid_fixture["expectedDigest"]

    duplicate_key_fixture = load_json_fixture(FIXTURES.canonical_json_dir / "duplicate-key-invalid.json")
    with pytest.raises(CanonicalJsonError) as error_info:
        runtime_protocol.canonical_bytes(duplicate_key_fixture["input"])
    assert error_info.value.code == duplicate_key_fixture["expectedErrorCode"]


def test_runtime_manifest_validator_uses_sdk_with_protocol_schema_dir() -> None:
    assert runtime_protocol.default_protocol_schema_dir() == FIXTURES.schema_dir

    valid_manifest = load_json_fixture(FIXTURES.manifest_valid_dir / "config-ui-schema.json")["manifest"]
    valid_result = runtime_protocol.validate_manifest_against_protocol_schema(valid_manifest)
    assert valid_result.valid, valid_result.errors

    invalid_fixture = load_json_fixture(FIXTURES.manifest_invalid_dir / "empty-descriptors.json")
    invalid_result = runtime_protocol.validate_manifest_against_protocol_schema(invalid_fixture["manifest"])
    assert not invalid_result.valid
    assert any(error.code == invalid_fixture["expectedErrorCode"] for error in invalid_result.errors), invalid_result.errors


def test_runtime_extension_error_parser_uses_sdk_error_contract() -> None:
    error = runtime_protocol.parse_non_2xx_extension_error(
        401,
        FIXTURES.extension_error_example_path.read_text(encoding="utf-8"),
    )

    assert error.error_code == "REMOTE_AUTH_FAILED"
    assert error.category is ExtensionErrorCategory.AUTH
    assert error.retryable is False

    with pytest.raises(ExtensionErrorParseError):
        runtime_protocol.parse_non_2xx_extension_error(
            200,
            FIXTURES.extension_error_example_path.read_text(encoding="utf-8"),
        )
