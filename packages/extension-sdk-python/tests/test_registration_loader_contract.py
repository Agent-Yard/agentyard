from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest

from lynxus_extension_sdk.registration import (
    ExtensionRegistrationSet,
    RegistrationConfigError,
    load_registration_yaml,
)
from lynxus_extension_sdk.testing import load_json_fixture


REPO_ROOT = Path(__file__).resolve().parents[3]
FIXTURES_DIR = REPO_ROOT / "packages/extension-protocol/contract-tests/fixtures/registration-loader"


@pytest.mark.parametrize("fixture_path", sorted(FIXTURES_DIR.glob("*.json")), ids=lambda path: path.name)
def test_registration_loader_fixtures_match_protocol_contract(fixture_path: Path) -> None:
    fixture = load_json_fixture(fixture_path)
    expected_error_code = fixture["expectedErrorCode"]

    if expected_error_code is None:
        expected = fixture["expected"]
        loaded = load_registration_yaml(fixture["operatorYaml"], fixture["environment"])
        _assert_loaded_matches_expected(loaded, expected)

        alternate = load_registration_yaml(fixture["operatorYaml"], fixture["alternateEnvironment"])
        _assert_loaded_matches_expected(alternate, expected)

        variant = load_registration_yaml(fixture["variantOperatorYaml"], fixture["alternateEnvironment"])
        _assert_loaded_matches_expected(variant, expected)
        return

    with pytest.raises(RegistrationConfigError) as error:
        load_registration_yaml(fixture["operatorYaml"], fixture["environment"])
    assert error.value.code.value == expected_error_code


def _assert_loaded_matches_expected(loaded: ExtensionRegistrationSet, expected: dict[str, Any]) -> None:
    assert loaded.registration_config_digest == expected["registrationConfigDigest"]
    assert loaded.canonical_input == expected["canonicalInput"]
    expected_ids = [service["registrationId"] for service in expected["canonicalInput"]["services"]]
    assert [service.registration_id for service in loaded.services] == expected_ids
