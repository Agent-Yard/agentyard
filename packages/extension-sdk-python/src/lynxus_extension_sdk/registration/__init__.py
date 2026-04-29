"""Static extension registration loading, normalization, and digest helpers."""

from __future__ import annotations

from collections.abc import Callable, Mapping
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
import re
from typing import Any
from urllib.parse import urlsplit

import yaml

from lynxus_extension_sdk.common.canonical_json import sha256_digest


CORE_CHANNEL_GATEWAY_REGISTRATION_ID = "core-channel-gateway"
CORE_AGENT_RUNTIME_REGISTRATION_ID = "core-agent-runtime"
CHANNEL_GATEWAY_URL_ENV = "LYNXUS_CHANNEL_GATEWAY_URL"
AGENT_RUNTIME_URL_ENV = "LYNXUS_AGENT_RUNTIME_URL"

_ENV_PLACEHOLDER = re.compile(r"^\$\{([A-Za-z_][A-Za-z0-9_]*)}$")
_URL_PATH = re.compile(r"^(?:[A-Za-z0-9._~!$&'()*+,;=:@/-]|%[0-9A-Fa-f]{2})*$")


class RegistrationConfigErrorCode(str, Enum):
    REGISTRATION_CONFIG_UNAVAILABLE = "REGISTRATION_CONFIG_UNAVAILABLE"
    REGISTRATION_CONFIG_INVALID = "REGISTRATION_CONFIG_INVALID"


class RegistrationConfigError(ValueError):
    def __init__(self, code: RegistrationConfigErrorCode, message: str) -> None:
        super().__init__(message)
        self.code = code


class RegistrationSource(str, Enum):
    CORE_PRESET = "CORE_PRESET"
    OPERATOR_YAML = "OPERATOR_YAML"


class RegistrationAuthType(str, Enum):
    INTERNAL_TOKEN = "INTERNAL_TOKEN"


@dataclass(frozen=True, slots=True)
class RegistrationAuth:
    type: RegistrationAuthType


@dataclass(frozen=True, slots=True)
class RegistrationExposes:
    channel_provider_types: tuple[str, ...] = ()
    tool_connector_types: tuple[str, ...] = ()

    @property
    def is_empty(self) -> bool:
        return not self.channel_provider_types and not self.tool_connector_types


@dataclass(frozen=True, slots=True)
class ExtensionRegistration:
    registration_id: str
    source: RegistrationSource
    base_url: str
    exposes: RegistrationExposes
    auth: RegistrationAuth


@dataclass(frozen=True, slots=True)
class ExtensionRegistrationSet:
    services: tuple[ExtensionRegistration, ...]
    registration_config_digest: str
    canonical_input: dict[str, Any]


EnvironmentResolver = Mapping[str, str] | Callable[[str], str | None]


def load_registration_file(
    path: Path | str,
    environment: EnvironmentResolver | None = None,
) -> ExtensionRegistrationSet:
    try:
        return load_registration_yaml(Path(path).read_text(encoding="utf-8"), environment)
    except OSError as error:
        raise RegistrationConfigError(
            RegistrationConfigErrorCode.REGISTRATION_CONFIG_UNAVAILABLE,
            f"Registration config is unavailable: {path}",
        ) from error


def load_registration_yaml(
    yaml_text: str | None,
    environment: EnvironmentResolver | None = None,
) -> ExtensionRegistrationSet:
    resolver = environment or {}
    services = [
        *_operator_registrations(yaml_text, resolver),
        _core_channel_gateway_preset(resolver),
        _core_agent_runtime_preset(resolver),
    ]
    ordered_services = tuple(sorted(services, key=lambda service: _utf16_sort_key(service.registration_id)))
    canonical_input = registration_config_digest_input(ordered_services)
    return ExtensionRegistrationSet(
        services=ordered_services,
        registration_config_digest=sha256_digest(canonical_input),
        canonical_input=canonical_input,
    )


def registration_config_digest_input(registrations: tuple[ExtensionRegistration, ...] | list[ExtensionRegistration]) -> dict[str, Any]:
    services = []
    for registration in sorted(registrations, key=lambda service: _utf16_sort_key(service.registration_id)):
        services.append(
            {
                "registrationId": registration.registration_id,
                "source": registration.source.value,
                "baseUrl": registration.base_url,
                "exposes": {
                    "channelProviderTypes": list(_sorted_unique(registration.exposes.channel_provider_types)),
                    "toolConnectorTypes": list(_sorted_unique(registration.exposes.tool_connector_types)),
                },
                "auth": {
                    "type": registration.auth.type.value,
                },
            }
        )
    return {"services": services}


def registration_config_digest(registrations: tuple[ExtensionRegistration, ...] | list[ExtensionRegistration]) -> str:
    return sha256_digest(registration_config_digest_input(registrations))


def normalize_base_url(value: str, environment: EnvironmentResolver | None = None) -> str:
    resolved = _resolve_placeholder(_required_string(value, "baseUrl"), environment or {})
    if any(char.isspace() for char in resolved):
        raise _invalid(f"Invalid baseUrl {value}")
    try:
        parsed = urlsplit(resolved)
    except ValueError as error:
        raise _invalid(f"Invalid baseUrl {value}") from error
    scheme = parsed.scheme.lower()
    if scheme not in {"http", "https"}:
        raise _invalid("baseUrl scheme must be http or https")
    try:
        port = parsed.port
    except ValueError as error:
        raise _invalid(f"Invalid baseUrl {value}") from error
    if parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.hostname is None:
        raise _invalid("baseUrl must not contain userinfo, query, or fragment")
    if any(char.isspace() for char in parsed.netloc):
        raise _invalid("baseUrl host must not contain whitespace")
    if not _URL_PATH.fullmatch(parsed.path):
        raise _invalid(f"Invalid baseUrl {value}")

    host = parsed.hostname.lower()
    if ":" not in host and not re.fullmatch(r"[a-z0-9.-]+", host):
        raise _invalid("baseUrl host is invalid")
    host_for_authority = f"[{host}]" if ":" in host else host
    default_port = (scheme == "http" and port == 80) or (scheme == "https" and port == 443)
    authority = host_for_authority if port is None or default_port else f"{host_for_authority}:{port}"
    path = "" if parsed.path in {"", "/"} else parsed.path.rstrip("/")
    return f"{scheme}://{authority}{path}"


def _operator_registrations(yaml_text: str | None, environment: EnvironmentResolver) -> tuple[ExtensionRegistration, ...]:
    document = _load_yaml(yaml_text)
    lynxus = document.get("lynxus")
    if lynxus is None:
        services = []
    else:
        _require_mapping(lynxus, "lynxus")
        extensions = lynxus.get("extensions")
        if extensions is None:
            services = []
        else:
            _require_mapping(extensions, "lynxus.extensions")
            services = extensions.get("services")
            if services is None:
                services = []
    if not isinstance(services, list):
        raise _invalid("lynxus.extensions.services must be a list")

    registrations = []
    seen_ids = set()
    for service in services:
        registration = _operator_registration(service, environment)
        if registration.registration_id in seen_ids:
            raise _invalid(f"Duplicate registrationId {registration.registration_id}")
        seen_ids.add(registration.registration_id)
        registrations.append(registration)
    return tuple(registrations)


def _operator_registration(service: Any, environment: EnvironmentResolver) -> ExtensionRegistration:
    _require_mapping(service, "service")
    _require_only_fields(service, {"registrationId", "baseUrl", "exposes", "auth"}, "service")
    registration_id = _required_string(service.get("registrationId"), "registrationId")
    if registration_id.startswith("core-"):
        raise _invalid(f"Operator registrationId must not use reserved core- prefix: {registration_id}")

    exposes = _exposes(service.get("exposes"))
    if exposes.is_empty:
        raise _invalid(f"Registration must expose at least one descriptor type: {registration_id}")
    return ExtensionRegistration(
        registration_id=registration_id,
        source=RegistrationSource.OPERATOR_YAML,
        base_url=normalize_base_url(_required_string(service.get("baseUrl"), "baseUrl"), environment),
        exposes=exposes,
        auth=_auth(service.get("auth")),
    )


def _core_channel_gateway_preset(environment: EnvironmentResolver) -> ExtensionRegistration:
    return ExtensionRegistration(
        registration_id=CORE_CHANNEL_GATEWAY_REGISTRATION_ID,
        source=RegistrationSource.CORE_PRESET,
        base_url=normalize_base_url(f"${{{CHANNEL_GATEWAY_URL_ENV}}}", environment),
        exposes=RegistrationExposes(channel_provider_types=("feishu",)),
        auth=RegistrationAuth(RegistrationAuthType.INTERNAL_TOKEN),
    )


def _core_agent_runtime_preset(environment: EnvironmentResolver) -> ExtensionRegistration:
    return ExtensionRegistration(
        registration_id=CORE_AGENT_RUNTIME_REGISTRATION_ID,
        source=RegistrationSource.CORE_PRESET,
        base_url=normalize_base_url(f"${{{AGENT_RUNTIME_URL_ENV}}}", environment),
        exposes=RegistrationExposes(tool_connector_types=("business-code-secret-http", "mcp", "simple-http")),
        auth=RegistrationAuth(RegistrationAuthType.INTERNAL_TOKEN),
    )


class _UniqueKeySafeLoader(yaml.SafeLoader):
    pass


def _construct_mapping(loader: yaml.SafeLoader, node: yaml.Node, deep: bool = False) -> dict[Any, Any]:
    loader.flatten_mapping(node)
    mapping: dict[Any, Any] = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise _invalid(f"Duplicate YAML key {key}")
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


_UniqueKeySafeLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _construct_mapping)


def _load_yaml(yaml_text: str | None) -> dict[str, Any]:
    try:
        document = yaml.load(yaml_text or "", Loader=_UniqueKeySafeLoader)
    except RegistrationConfigError:
        raise
    except yaml.YAMLError as error:
        raise _invalid("Registration YAML is invalid") from error
    if document is None:
        return {}
    _require_mapping(document, "root")
    return document


def _exposes(value: Any) -> RegistrationExposes:
    if value is None:
        return RegistrationExposes()
    _require_mapping(value, "exposes")
    _require_only_fields(value, {"channelProviderTypes", "toolConnectorTypes"}, "exposes")
    return RegistrationExposes(
        channel_provider_types=tuple(_string_list(value.get("channelProviderTypes"), "exposes.channelProviderTypes")),
        tool_connector_types=tuple(_string_list(value.get("toolConnectorTypes"), "exposes.toolConnectorTypes")),
    )


def _auth(value: Any) -> RegistrationAuth:
    _require_mapping(value, "auth")
    _require_only_fields(value, {"type"}, "auth")
    auth_type = _required_string(value.get("type"), "auth.type")
    if auth_type != RegistrationAuthType.INTERNAL_TOKEN.value:
        raise _invalid(f"Unsupported auth.type {auth_type}")
    return RegistrationAuth(RegistrationAuthType.INTERNAL_TOKEN)


def _string_list(value: Any, label: str) -> tuple[str, ...]:
    if value is None:
        return ()
    if not isinstance(value, list):
        raise _invalid(f"{label} must be a list")
    return _sorted_unique(_required_string(item, f"{label}[]") for item in value)


def _resolve_placeholder(value: str, environment: EnvironmentResolver) -> str:
    match = _ENV_PLACEHOLDER.fullmatch(value)
    if not match:
        if "${" in value:
            raise _invalid("baseUrl placeholder must occupy the full value")
        return value
    key = match.group(1)
    resolved = _resolve_environment(environment, key)
    if resolved is None or not str(resolved).strip():
        raise _invalid(f"Missing environment value for {key}")
    return str(resolved).strip()


def _resolve_environment(environment: EnvironmentResolver, key: str) -> str | None:
    if isinstance(environment, Mapping):
        return environment.get(key)
    return environment(key)


def _require_mapping(value: Any, label: str) -> None:
    if not isinstance(value, dict):
        raise _invalid(f"{label} must be an object")


def _require_only_fields(value: Mapping[str, Any], allowed_fields: set[str], label: str) -> None:
    for field in value:
        if field not in allowed_fields:
            raise _invalid(f"{label} contains unsupported field {field}")


def _required_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise _invalid(f"{label} must be a non-empty string")
    return value.strip()


def _sorted_unique(values: Any) -> tuple[str, ...]:
    return tuple(sorted(set(values), key=_utf16_sort_key))


def _utf16_sort_key(value: str) -> bytes:
    return value.encode("utf-16-be")


def _invalid(message: str) -> RegistrationConfigError:
    return RegistrationConfigError(RegistrationConfigErrorCode.REGISTRATION_CONFIG_INVALID, message)


__all__ = [
    "AGENT_RUNTIME_URL_ENV",
    "CHANNEL_GATEWAY_URL_ENV",
    "CORE_AGENT_RUNTIME_REGISTRATION_ID",
    "CORE_CHANNEL_GATEWAY_REGISTRATION_ID",
    "ExtensionRegistration",
    "ExtensionRegistrationSet",
    "RegistrationAuth",
    "RegistrationAuthType",
    "RegistrationConfigError",
    "RegistrationConfigErrorCode",
    "RegistrationExposes",
    "RegistrationSource",
    "load_registration_file",
    "load_registration_yaml",
    "normalize_base_url",
    "registration_config_digest",
    "registration_config_digest_input",
]
