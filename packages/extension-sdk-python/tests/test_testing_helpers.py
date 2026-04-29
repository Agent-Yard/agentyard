from __future__ import annotations

from pathlib import Path

from lynxus_extension_sdk.testing import default_protocol_fixtures, load_json_fixture


def test_testing_helpers_expose_shared_protocol_fixture_roots() -> None:
    fixtures = default_protocol_fixtures(Path(__file__))

    assert fixtures.schema_dir.name == "json-schema"
    assert fixtures.service_manifest_schema_path.is_file()
    assert fixtures.extension_error_example_path.is_file()
    assert any(path.name == "string-escape-unicode.json" for path in fixtures.canonical_json_fixtures())
    assert any(path.name == "config-ui-schema.json" for path in fixtures.valid_manifest_fixtures())
    assert any(path.name == "empty-descriptors.json" for path in fixtures.invalid_manifest_fixtures())
    assert any(path.name == "valid-operator-with-presets.json" for path in fixtures.registration_loader_fixtures())


def test_load_json_fixture_reads_shared_fixture_document() -> None:
    fixtures = default_protocol_fixtures(Path(__file__))

    payload = load_json_fixture(fixtures.extension_error_example_path)

    assert payload["errorCode"] == "REMOTE_AUTH_FAILED"
