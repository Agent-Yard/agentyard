from __future__ import annotations

from pathlib import Path

from lynxus_agent_runtime import extension_registration as runtime_registration
from lynxus_extension_sdk.registration import (
    AGENT_RUNTIME_BASE_URL_ENV,
    CHANNEL_GATEWAY_BASE_URL_ENV,
    CORE_AGENT_RUNTIME_REGISTRATION_ID,
    CORE_CHANNEL_GATEWAY_REGISTRATION_ID,
    load_registration_file as sdk_load_registration_file,
    load_registration_yaml as sdk_load_registration_yaml,
)
from lynxus_extension_sdk.testing import load_json_fixture


REPO_ROOT = Path(__file__).resolve().parents[3]
REGISTRATION_FIXTURE = (
    REPO_ROOT
    / "packages/extension-protocol/contract-tests/fixtures/registration-loader/valid-operator-with-presets.json"
)


def test_runtime_registration_adapter_reexports_sdk_loader_functions() -> None:
    assert runtime_registration.load_registration_file is sdk_load_registration_file
    assert runtime_registration.load_registration_yaml is sdk_load_registration_yaml


def test_runtime_loads_configured_operator_file_with_shared_sdk_digest(tmp_path: Path) -> None:
    fixture = load_json_fixture(REGISTRATION_FIXTURE)
    registration_file = tmp_path / "extensions.yaml"
    registration_file.write_text(fixture["operatorYaml"], encoding="utf-8")
    environment = {
        **fixture["environment"],
        runtime_registration.EXTENSION_REGISTRATION_FILE_ENV: str(registration_file),
    }

    loaded = runtime_registration.load_extension_registration(environment)
    sdk_loaded = sdk_load_registration_file(registration_file, fixture["environment"])

    assert loaded.registration_config_digest == fixture["expected"]["registrationConfigDigest"]
    assert loaded.canonical_input == fixture["expected"]["canonicalInput"]
    assert loaded.registration_config_digest == sdk_loaded.registration_config_digest


def test_runtime_uses_core_presets_when_operator_file_is_unset() -> None:
    environment = {
        CHANNEL_GATEWAY_BASE_URL_ENV: "HTTP://Channel-Gateway.Example.COM:80/core/",
        AGENT_RUNTIME_BASE_URL_ENV: "https://Agent-Runtime.Example.COM:443/runtime/",
    }

    loaded = runtime_registration.load_extension_registration(environment)
    sdk_loaded = sdk_load_registration_yaml("", environment)

    assert loaded.registration_config_digest == sdk_loaded.registration_config_digest
    assert loaded.canonical_input == sdk_loaded.canonical_input
    assert [service.registration_id for service in loaded.services] == [
        CORE_AGENT_RUNTIME_REGISTRATION_ID,
        CORE_CHANNEL_GATEWAY_REGISTRATION_ID,
    ]


def test_runtime_uses_local_core_url_defaults_when_env_is_unset() -> None:
    loaded = runtime_registration.load_extension_registration({})

    base_urls = {service.registration_id: service.base_url for service in loaded.services}
    assert base_urls[CORE_CHANNEL_GATEWAY_REGISTRATION_ID] == "http://127.0.0.1:8082"
    assert base_urls[CORE_AGENT_RUNTIME_REGISTRATION_ID] == "http://127.0.0.1:8090"
