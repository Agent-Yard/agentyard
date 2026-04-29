from __future__ import annotations

import json
from collections.abc import Callable
from pathlib import Path

import pytest

from lynxus_extension_sdk.validation import validate_manifest_object


REPO_ROOT = Path(__file__).resolve().parents[3]
SCHEMA_DIR = REPO_ROOT / "packages/extension-protocol/json-schema"
VALID_FIXTURES = REPO_ROOT / "packages/extension-protocol/contract-tests/fixtures/manifest-valid"
INVALID_FIXTURES = REPO_ROOT / "packages/extension-protocol/contract-tests/fixtures/manifest-invalid"


@pytest.mark.parametrize("fixture_path", sorted(VALID_FIXTURES.glob("*.json")), ids=lambda path: path.name)
def test_accepts_shared_valid_manifest_fixtures(fixture_path: Path) -> None:
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))

    result = validate_manifest_object(fixture["manifest"], schema_dir=SCHEMA_DIR)

    assert result.valid, result.errors


@pytest.mark.parametrize("fixture_path", sorted(INVALID_FIXTURES.glob("*.json")), ids=lambda path: path.name)
def test_rejects_shared_invalid_manifest_fixtures_with_expected_code(fixture_path: Path) -> None:
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
    expected_error_code = fixture["expectedErrorCode"]

    result = validate_manifest_object(fixture["manifest"], schema_dir=SCHEMA_DIR)

    assert not result.valid
    assert any(error.code == expected_error_code for error in result.errors), result.errors


@pytest.mark.parametrize(
    "manifest",
    [
        lambda: _base_valid_tool_manifest(envelope_extra={"unexpected": True}),
        lambda: _base_valid_tool_manifest(descriptor_extra={"unexpectedDescriptorField": True}),
        lambda: _base_valid_tool_manifest(endpoint_extra={"deleteCredential": "/credentials/delete"}),
        lambda: _base_valid_tool_manifest(connector_type="bad connector type"),
        lambda: {
            "extensionApiVersion": 1,
            "coreMinVersion": "0.1.0",
            "descriptors": {"channelProviders": [], "toolConnectors": []},
        },
        lambda: _base_valid_tool_manifest(invoke_path="relative/path"),
    ],
    ids=[
        "top-level-additional-property",
        "descriptor-additional-property",
        "endpoint-additional-property",
        "descriptor-id-pattern",
        "missing-required-field",
        "endpoint-path-pattern",
    ],
)
def test_rejects_manifest_shapes_enforced_by_json_schema(manifest: Callable[[], object]) -> None:
    result = validate_manifest_object(manifest(), schema_dir=SCHEMA_DIR)

    assert not result.valid
    assert any(error.code == "MANIFEST_SCHEMA_INVALID" for error in result.errors), result.errors


def test_rejects_empty_descriptor_envelope_through_schema_and_semantic_validation() -> None:
    result = validate_manifest_object(
        {
            "extensionApiVersion": 1,
            "coreMinVersion": "0.1.0",
            "coreMaxVersion": "0.1.x",
            "descriptors": {"channelProviders": [], "toolConnectors": []},
        },
        schema_dir=SCHEMA_DIR,
    )

    assert not result.valid
    assert any(error.code == "MANIFEST_SCHEMA_INVALID" for error in result.errors), result.errors
    assert any(error.code == "MANIFEST_EMPTY" for error in result.errors), result.errors


def _base_valid_tool_manifest(
    *,
    envelope_extra: dict[str, object] | None = None,
    descriptor_extra: dict[str, object] | None = None,
    endpoint_extra: dict[str, object] | None = None,
    connector_type: str = "contract-test.tool",
    invoke_path: str = "/tools/invoke",
) -> dict[str, object]:
    endpoints: dict[str, object] = {"invoke": invoke_path}
    endpoints.update(endpoint_extra or {})

    descriptor: dict[str, object] = {
        "connectorType": connector_type,
        "title": "Contract test tool",
        "accountConfigSchema": {"type": "object", "properties": {}},
        "accountConfigUiSchema": [],
        "configSchema": {"type": "object", "properties": {}},
        "configUiSchema": [],
        "operationMappingSchema": {"type": "object", "properties": {}},
        "operationMappingUiSchema": [],
        "endpoints": endpoints,
    }
    descriptor.update(descriptor_extra or {})

    manifest: dict[str, object] = {
        "extensionApiVersion": 1,
        "coreMinVersion": "0.1.0",
        "coreMaxVersion": "0.1.x",
        "descriptors": {"channelProviders": [], "toolConnectors": [descriptor]},
    }
    manifest.update(envelope_extra or {})
    return manifest
