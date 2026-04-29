from __future__ import annotations

from collections.abc import Callable, Mapping
import os
from pathlib import Path

from lynxus_extension_sdk.registration import (
    AGENT_RUNTIME_URL_ENV,
    CHANNEL_GATEWAY_URL_ENV,
    CORE_AGENT_RUNTIME_REGISTRATION_ID,
    CORE_CHANNEL_GATEWAY_REGISTRATION_ID,
    ExtensionRegistration,
    ExtensionRegistrationSet,
    RegistrationConfigError,
    load_registration_file,
    load_registration_yaml,
)


EXTENSION_REGISTRATION_FILE_ENV = "LYNXUS_EXTENSION_REGISTRATION_FILE"
DEFAULT_CHANNEL_GATEWAY_URL = "http://127.0.0.1:8082"
DEFAULT_AGENT_RUNTIME_URL = "http://127.0.0.1:8090"

Environment = Mapping[str, str] | Callable[[str], str | None]


def load_extension_registration(environment: Environment | None = None) -> ExtensionRegistrationSet:
    source = os.environ if environment is None else environment
    resolver = _registration_environment(source)
    registration_file = _read_environment(source, EXTENSION_REGISTRATION_FILE_ENV)
    if registration_file is None:
        return load_registration_yaml("", resolver)
    return load_registration_file(Path(registration_file), resolver)


def _registration_environment(environment: Environment) -> Callable[[str], str | None]:
    def resolve(key: str) -> str | None:
        value = _read_environment(environment, key)
        if value is not None:
            return value
        if key == CHANNEL_GATEWAY_URL_ENV:
            return DEFAULT_CHANNEL_GATEWAY_URL
        if key == AGENT_RUNTIME_URL_ENV:
            return DEFAULT_AGENT_RUNTIME_URL
        return None

    return resolve


def _read_environment(environment: Environment, key: str) -> str | None:
    value = environment.get(key) if isinstance(environment, Mapping) else environment(key)
    if value is None:
        return None
    text = str(value).strip()
    return text or None


__all__ = [
    "AGENT_RUNTIME_URL_ENV",
    "CHANNEL_GATEWAY_URL_ENV",
    "CORE_AGENT_RUNTIME_REGISTRATION_ID",
    "CORE_CHANNEL_GATEWAY_REGISTRATION_ID",
    "DEFAULT_AGENT_RUNTIME_URL",
    "DEFAULT_CHANNEL_GATEWAY_URL",
    "EXTENSION_REGISTRATION_FILE_ENV",
    "ExtensionRegistration",
    "ExtensionRegistrationSet",
    "RegistrationConfigError",
    "load_extension_registration",
    "load_registration_file",
    "load_registration_yaml",
]
