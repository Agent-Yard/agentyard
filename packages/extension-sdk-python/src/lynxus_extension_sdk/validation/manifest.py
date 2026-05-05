"""Service manifest validator constrained by shared JSON Schema fixtures."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping

from jsonschema import Draft202012Validator
from jsonschema.exceptions import SchemaError, ValidationError
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

from lynxus_extension_sdk.protocol import (
    CHANNEL_PROVIDER_RUN_JOB_ENDPOINT,
    CREATE_CREDENTIAL_ENDPOINT,
    EXTENSION_API_VERSION,
    REVOKE_CREDENTIAL_ENDPOINT,
    ROTATE_CREDENTIAL_ENDPOINT,
    TOOL_CONNECTOR_INVOKE_ENDPOINT,
    VALIDATE_CREDENTIAL_ENDPOINT,
)

MANIFEST_SCHEMA_INVALID = "MANIFEST_SCHEMA_INVALID"

_CREDENTIAL_ENDPOINTS = frozenset(
    {
        CREATE_CREDENTIAL_ENDPOINT,
        ROTATE_CREDENTIAL_ENDPOINT,
        REVOKE_CREDENTIAL_ENDPOINT,
        VALIDATE_CREDENTIAL_ENDPOINT,
    }
)
_OPTION_COMPONENTS = frozenset({"select", "multiSelect", "radio", "checkboxGroup"})
_VISIBILITY_OPERATORS = frozenset({"equals", "notEquals", "in", "notIn", "exists", "notExists"})
_PROTOCOL_SCHEMA_FILES = (
    "assistant-binding.schema.json",
    "channel-outbound-frame.schema.json",
    "channel-outbound-frame-ack.schema.json",
    "channel-outbound-frame-subscription.schema.json",
    "channel-provider-descriptor.schema.json",
    "extension-error.schema.json",
    "external-template-binding.schema.json",
    "job-definition.schema.json",
    "schedule-config.schema.json",
    "service-manifest.schema.json",
    "tool-connector-descriptor.schema.json",
    "ui-field.schema.json",
)
_SERVICE_MANIFEST_SCHEMA_ID = "https://lynxus.dev/schemas/extension-protocol/service-manifest.schema.json"


@dataclass(frozen=True, slots=True)
class ManifestValidationError:
    code: str
    path: str
    message: str


@dataclass(frozen=True, slots=True)
class ManifestValidationResult:
    errors: tuple[ManifestValidationError, ...]

    @property
    def valid(self) -> bool:
        return not self.errors


def validate_manifest_json(raw_json: str, *, schema_dir: Path | None = None) -> ManifestValidationResult:
    return validate_manifest_object(json.loads(raw_json), schema_dir=schema_dir)


def validate_manifest_object(manifest: object, *, schema_dir: Path | None = None) -> ManifestValidationResult:
    errors: list[ManifestValidationError] = []
    if schema_dir is not None:
        schemas = _load_protocol_schema_assets(schema_dir, errors)
        if schemas is not None:
            _validate_against_service_manifest_schema(manifest, schemas, errors)
    _validate_manifest(manifest, errors)
    return ManifestValidationResult(tuple(errors))


def _load_protocol_schema_assets(
    schema_dir: Path,
    errors: list[ManifestValidationError],
) -> dict[str, dict[str, Any]] | None:
    schemas: dict[str, dict[str, Any]] = {}
    for file_name in _PROTOCOL_SCHEMA_FILES:
        schema_file = schema_dir / file_name
        if not schema_file.is_file():
            _add(errors, "PROTOCOL_SCHEMA_ASSET_MISSING", f"/schemas/{file_name}", "Protocol JSON Schema asset is missing")
            return None
        try:
            schema = json.loads(schema_file.read_text(encoding="utf-8"))
            Draft202012Validator.check_schema(schema)
            schema_id = schema.get("$id")
            if not isinstance(schema_id, str) or not schema_id:
                _add(errors, "PROTOCOL_SCHEMA_ASSET_INVALID", f"/schemas/{file_name}", "Protocol JSON Schema asset is missing $id")
                return None
            schemas[schema_id] = schema
        except (OSError, json.JSONDecodeError, SchemaError):
            _add(errors, "PROTOCOL_SCHEMA_ASSET_INVALID", f"/schemas/{file_name}", "Protocol JSON Schema asset is invalid")
            return None
    return schemas


def _validate_against_service_manifest_schema(
    manifest: object,
    schemas: Mapping[str, dict[str, Any]],
    errors: list[ManifestValidationError],
) -> None:
    schema = schemas.get(_SERVICE_MANIFEST_SCHEMA_ID)
    if schema is None:
        _add(errors, "PROTOCOL_SCHEMA_ASSET_MISSING", "/schemas/service-manifest.schema.json", "Service manifest schema is missing")
        return

    registry = Registry().with_resources(
        (schema_id, Resource.from_contents(schema_value, default_specification=DRAFT202012))
        for schema_id, schema_value in schemas.items()
    )
    validator = Draft202012Validator(schema, registry=registry)
    for error in sorted(validator.iter_errors(manifest), key=lambda item: tuple(str(part) for part in item.absolute_path)):
        _add(errors, MANIFEST_SCHEMA_INVALID, _error_pointer(error), error.message)


def _error_pointer(error: ValidationError) -> str:
    parts = [str(part).replace("~", "~0").replace("/", "~1") for part in error.absolute_path]
    return "/" + "/".join(parts) if parts else ""


def _validate_manifest(value: object, errors: list[ManifestValidationError]) -> None:
    manifest = _object(value, "", errors)
    if not manifest and not isinstance(value, Mapping):
        return
    _require_integer_const(manifest, "extensionApiVersion", EXTENSION_API_VERSION, "", errors)
    _require_string(manifest, "coreMinVersion", 1, 64, "", errors)
    _require_string(manifest, "coreMaxVersion", 1, 64, "", errors)

    descriptors = _object(manifest.get("descriptors"), "/descriptors", errors)
    channel_providers = _array(descriptors.get("channelProviders"), "/descriptors/channelProviders", errors)
    tool_connectors = _array(descriptors.get("toolConnectors"), "/descriptors/toolConnectors", errors)
    if not channel_providers and not tool_connectors:
        _add(errors, "MANIFEST_EMPTY", "/descriptors", "Manifest must expose at least one descriptor")

    for index, descriptor in enumerate(channel_providers):
        _validate_channel_provider(
            _object(descriptor, f"/descriptors/channelProviders/{index}", errors),
            f"/descriptors/channelProviders/{index}",
            errors,
        )
    for index, descriptor in enumerate(tool_connectors):
        _validate_tool_connector(
            _object(descriptor, f"/descriptors/toolConnectors/{index}", errors),
            f"/descriptors/toolConnectors/{index}",
            errors,
        )


def _validate_channel_provider(descriptor: dict[str, Any], path: str, errors: list[ManifestValidationError]) -> None:
    _require_string(descriptor, "providerType", 1, 128, path, errors)
    _require_string(descriptor, "title", 1, 120, path, errors)
    _require_object_field(descriptor, "accountConfigSchema", path, errors)
    _require_array_field(descriptor, "accountConfigUiSchema", path, errors)
    _require_object_field(descriptor, "configSchema", path, errors)
    _require_array_field(descriptor, "configUiSchema", path, errors)
    _validate_channel_provider_outbound(_require_object_field(descriptor, "outbound", path, errors), f"{path}/outbound", errors)

    endpoints = _require_object_field(descriptor, "endpoints", path, errors)
    _validate_credential_endpoint_completeness(descriptor, endpoints, path, errors)

    _validate_ui_pair(
        _object_or_empty(descriptor.get("accountConfigSchema")),
        _array_or_empty(descriptor.get("accountConfigUiSchema")),
        credential_ui=False,
        path=f"{path}/accountConfigUiSchema",
        errors=errors,
    )
    _validate_ui_pair(
        _object_or_empty(descriptor.get("configSchema")),
        _array_or_empty(descriptor.get("configUiSchema")),
        credential_ui=False,
        path=f"{path}/configUiSchema",
        errors=errors,
    )
    if "credentialUiSchema" in descriptor:
        _validate_ui_pair(
            _object_or_empty(descriptor.get("credentialSchema")),
            _array_or_empty(descriptor.get("credentialUiSchema")),
            credential_ui=True,
            path=f"{path}/credentialUiSchema",
            errors=errors,
        )
    if _contains_secret_marker(descriptor.get("defaultConfig")):
        _add(errors, "UI_SCHEMA_SECRET_NOT_ALLOWED", f"{path}/defaultConfig", "Normal config defaults must not contain secret=true")

    for index, job in enumerate(_array_or_empty(descriptor.get("jobDefinitions"))):
        job_path = f"{path}/jobDefinitions/{index}"
        job_object = _object(job, job_path, errors)
        _require_string(job_object, "jobType", 1, 128, job_path, errors)
        _require_string(job_object, "title", 1, 120, job_path, errors)
        _validate_ui_pair(
            _object_or_empty(job_object.get("jobConfigSchema")),
            _array_or_empty(job_object.get("jobConfigUiSchema")),
            credential_ui=False,
            path=f"{job_path}/jobConfigUiSchema",
            errors=errors,
        )
        default_schedule = _object_or_empty(job_object.get("defaultSchedule"))
        if _contains_secret_marker(default_schedule.get("jobConfig")):
            _add(
                errors,
                "UI_SCHEMA_SECRET_NOT_ALLOWED",
                f"{job_path}/defaultSchedule/jobConfig",
                "Job config defaults must not contain secret=true",
        )


def _validate_channel_provider_outbound(
    outbound: dict[str, Any],
    path: str,
    errors: list[ManifestValidationError],
) -> None:
    _require_string(outbound, "mode", 1, 64, path, errors)
    for field in {
        "supportsTyping",
        "supportsDraftUpdate",
        "supportsFinalDelivery",
        "requiresIdempotentFinalDelivery",
    }:
        if not isinstance(outbound.get(field), bool):
            _add(errors, MANIFEST_SCHEMA_INVALID, f"{path}/{field}", "Outbound capability field must be boolean")
    if outbound.get("mode") != "FRAME_STREAM":
        _add(errors, MANIFEST_SCHEMA_INVALID, f"{path}/mode", "outbound.mode must be FRAME_STREAM")
    if outbound.get("supportsFinalDelivery") is not True:
        _add(errors, MANIFEST_SCHEMA_INVALID, f"{path}/supportsFinalDelivery", "outbound.supportsFinalDelivery must be true")
    if outbound.get("requiresIdempotentFinalDelivery") is not True:
        _add(
            errors,
            MANIFEST_SCHEMA_INVALID,
            f"{path}/requiresIdempotentFinalDelivery",
            "outbound.requiresIdempotentFinalDelivery must be true",
        )


def _validate_tool_connector(descriptor: dict[str, Any], path: str, errors: list[ManifestValidationError]) -> None:
    _require_string(descriptor, "connectorType", 1, 128, path, errors)
    _require_string(descriptor, "title", 1, 120, path, errors)
    _require_object_field(descriptor, "accountConfigSchema", path, errors)
    _require_array_field(descriptor, "accountConfigUiSchema", path, errors)
    _require_object_field(descriptor, "configSchema", path, errors)
    _require_array_field(descriptor, "configUiSchema", path, errors)
    _require_object_field(descriptor, "operationMappingSchema", path, errors)
    _require_array_field(descriptor, "operationMappingUiSchema", path, errors)

    endpoints = _require_object_field(descriptor, "endpoints", path, errors)
    _validate_declared_endpoint(endpoints, TOOL_CONNECTOR_INVOKE_ENDPOINT, f"{path}/endpoints", errors)
    _validate_credential_endpoint_completeness(descriptor, endpoints, path, errors)

    _validate_ui_pair(
        _object_or_empty(descriptor.get("accountConfigSchema")),
        _array_or_empty(descriptor.get("accountConfigUiSchema")),
        credential_ui=False,
        path=f"{path}/accountConfigUiSchema",
        errors=errors,
    )
    _validate_ui_pair(
        _object_or_empty(descriptor.get("configSchema")),
        _array_or_empty(descriptor.get("configUiSchema")),
        credential_ui=False,
        path=f"{path}/configUiSchema",
        errors=errors,
    )
    _validate_ui_pair(
        _object_or_empty(descriptor.get("operationMappingSchema")),
        _array_or_empty(descriptor.get("operationMappingUiSchema")),
        credential_ui=False,
        path=f"{path}/operationMappingUiSchema",
        errors=errors,
    )
    if "credentialUiSchema" in descriptor:
        _validate_ui_pair(
            _object_or_empty(descriptor.get("credentialSchema")),
            _array_or_empty(descriptor.get("credentialUiSchema")),
            credential_ui=True,
            path=f"{path}/credentialUiSchema",
            errors=errors,
        )


def _validate_credential_endpoint_completeness(
    descriptor: dict[str, Any],
    endpoints: dict[str, Any],
    path: str,
    errors: list[ManifestValidationError],
) -> None:
    has_any_credential_endpoint = any(endpoint in endpoints for endpoint in _CREDENTIAL_ENDPOINTS)
    if has_any_credential_endpoint and (not _CREDENTIAL_ENDPOINTS.issubset(endpoints) or "credentialSchema" not in descriptor):
        _add(
            errors,
            "CREDENTIAL_ENDPOINTS_INCOMPLETE",
            f"{path}/endpoints",
            "Credential endpoints require credentialSchema and all four lifecycle endpoint paths",
        )
    for endpoint in _CREDENTIAL_ENDPOINTS:
        if endpoint in endpoints:
            _validate_declared_endpoint(endpoints, endpoint, f"{path}/endpoints", errors)


def _validate_declared_endpoint(
    endpoints: dict[str, Any],
    key: str,
    path: str,
    errors: list[ManifestValidationError],
) -> None:
    value = endpoints.get(key)
    if not isinstance(value, str) or not value.startswith("/") or "?" in value or "#" in value:
        _add(errors, MANIFEST_SCHEMA_INVALID, f"{path}/{key}", "Endpoint path must match ^/[^?#]*$")


def _validate_ui_pair(
    data_schema: dict[str, Any],
    ui_schema: list[Any],
    *,
    credential_ui: bool,
    path: str,
    errors: list[ManifestValidationError],
) -> None:
    if not credential_ui and _contains_secret_marker(data_schema):
        _add(errors, "UI_SCHEMA_SECRET_NOT_ALLOWED", path.replace("UiSchema", "Schema"), "Normal config schema must not contain secret=true")

    seen_keys: set[str] = set()
    for index, raw_field in enumerate(ui_schema):
        field_path = f"{path}/{index}"
        field = _object(raw_field, field_path, errors)
        key = field.get("key")
        if not isinstance(key, str):
            _add(errors, MANIFEST_SCHEMA_INVALID, f"{field_path}/key", "UI field key is required")
            continue
        if key in seen_keys:
            _add(errors, MANIFEST_SCHEMA_INVALID, f"{field_path}/key", "UI field key must be unique")
        seen_keys.add(key)

        target = _resolve_schema_pointer(data_schema, key)
        if target is None:
            _add(errors, "UI_SCHEMA_PROPERTY_NOT_FOUND", f"{field_path}/key", "UI field key must reference a JSON Schema property")
            continue

        if not credential_ui and (field.get("secret") is True or field.get("component") == "password"):
            _add(errors, "UI_SCHEMA_SECRET_NOT_ALLOWED", field_path, "Secret UI controls are only allowed for credentialUiSchema")

        if field.get("required") is True and not _is_required(target.parent_schema, target.property_name):
            _add(
                errors,
                "UI_SCHEMA_REQUIRED_NOT_AUTHORITATIVE",
                f"{field_path}/required",
                "UI required=true must repeat JSON Schema required",
            )

        if "visibilityCondition" in field:
            _validate_visibility_condition(field["visibilityCondition"], data_schema, target, field_path, errors)

        if "options" in field or field.get("component") in _OPTION_COMPONENTS:
            _validate_options(field, target.property_schema, field_path, errors)


def _validate_visibility_condition(
    value: object,
    data_schema: dict[str, Any],
    target: "_PointerTarget",
    field_path: str,
    errors: list[ManifestValidationError],
) -> None:
    condition = _object(value, f"{field_path}/visibilityCondition", errors)
    condition_field = condition.get("field")
    if not isinstance(condition_field, str) or _resolve_schema_pointer(data_schema, condition_field) is None:
        _add(
            errors,
            "UI_SCHEMA_PROPERTY_NOT_FOUND",
            f"{field_path}/visibilityCondition/field",
            "visibilityCondition.field must reference a JSON Schema property",
        )
    operator = condition.get("operator")
    if not isinstance(operator, str) or operator not in _VISIBILITY_OPERATORS:
        _add(errors, MANIFEST_SCHEMA_INVALID, f"{field_path}/visibilityCondition/operator", "Unsupported visibility operator")
    elif operator in {"in", "notIn"} and not _array_or_empty(condition.get("value")):
        _add(errors, MANIFEST_SCHEMA_INVALID, f"{field_path}/visibilityCondition/value", "in/notIn visibility value must be non-empty array")

    if _is_required(target.parent_schema, target.property_name):
        _add(
            errors,
            "UI_SCHEMA_VISIBILITY_REQUIRED_CONFLICT",
            f"{field_path}/visibilityCondition",
            "Unconditionally required fields must not be hidden by UI visibility",
        )


def _validate_options(
    field: dict[str, Any],
    property_schema: dict[str, Any],
    field_path: str,
    errors: list[ManifestValidationError],
) -> None:
    expected_values = _static_enum_values(property_schema)
    option_values = [_object(option, f"{field_path}/options", errors).get("value") for option in _array_or_empty(field.get("options"))]
    if expected_values is None or expected_values != option_values:
        _add(errors, "UI_SCHEMA_OPTIONS_DRIFT", f"{field_path}/options", "UI options must match JSON Schema static enum values")


def _static_enum_values(property_schema: dict[str, Any]) -> list[Any] | None:
    enum_values = property_schema.get("enum")
    if isinstance(enum_values, list):
        return list(enum_values)
    one_of = property_schema.get("oneOf")
    if isinstance(one_of, list):
        values = []
        for item in one_of:
            branch = _object_or_empty(item)
            if "const" not in branch:
                return None
            values.append(branch["const"])
        return values
    return None


@dataclass(frozen=True, slots=True)
class _PointerTarget:
    parent_schema: dict[str, Any]
    property_name: str
    property_schema: dict[str, Any]


def _resolve_schema_pointer(schema: dict[str, Any], pointer: str | None) -> _PointerTarget | None:
    if pointer is None or not pointer.startswith("/") or pointer == "/":
        return None
    current = schema
    parts = pointer[1:].split("/")
    for index, raw_part in enumerate(parts):
        part = raw_part.replace("~1", "/").replace("~0", "~")
        properties = _object_or_empty(current.get("properties"))
        next_schema = properties.get(part)
        if not isinstance(next_schema, Mapping):
            return None
        next_schema_object = dict(next_schema)
        if index == len(parts) - 1:
            return _PointerTarget(current, part, next_schema_object)
        current = next_schema_object
    return None


def _is_required(schema: dict[str, Any], property_name: str) -> bool:
    return property_name in _array_or_empty(schema.get("required"))


def _contains_secret_marker(value: object) -> bool:
    if isinstance(value, Mapping):
        for key, nested in value.items():
            if key == "secret" and nested is True:
                return True
            if _contains_secret_marker(nested):
                return True
    if isinstance(value, list):
        return any(_contains_secret_marker(item) for item in value)
    return False


def _require_object_field(
    value: Mapping[str, Any],
    field: str,
    path: str,
    errors: list[ManifestValidationError],
) -> dict[str, Any]:
    if field not in value:
        _add(errors, MANIFEST_SCHEMA_INVALID, f"{path}/{field}", "Required object field is missing")
        return {}
    return _object(value[field], f"{path}/{field}", errors)


def _require_array_field(
    value: Mapping[str, Any],
    field: str,
    path: str,
    errors: list[ManifestValidationError],
) -> list[Any]:
    if field not in value:
        _add(errors, MANIFEST_SCHEMA_INVALID, f"{path}/{field}", "Required array field is missing")
        return []
    return _array(value[field], f"{path}/{field}", errors)


def _require_string(
    value: Mapping[str, Any],
    field: str,
    min_length: int,
    max_length: int,
    base_path: str,
    errors: list[ManifestValidationError],
) -> None:
    raw = value.get(field)
    if not isinstance(raw, str) or len(raw) < min_length or len(raw) > max_length:
        _add(errors, MANIFEST_SCHEMA_INVALID, f"{base_path}/{field}", "Required string field is invalid")


def _require_integer_const(
    value: Mapping[str, Any],
    field: str,
    expected: int,
    base_path: str,
    errors: list[ManifestValidationError],
) -> None:
    raw = value.get(field)
    if type(raw) is not int or raw != expected:
        _add(errors, MANIFEST_SCHEMA_INVALID, f"{base_path}/{field}", "Required integer const is invalid")


def _object(value: object, path: str, errors: list[ManifestValidationError]) -> dict[str, Any]:
    if isinstance(value, Mapping):
        return dict(value)
    _add(errors, MANIFEST_SCHEMA_INVALID, path, "Expected object")
    return {}


def _array(value: object, path: str, errors: list[ManifestValidationError]) -> list[Any]:
    if isinstance(value, list):
        return list(value)
    _add(errors, MANIFEST_SCHEMA_INVALID, path, "Expected array")
    return []


def _object_or_empty(value: object) -> dict[str, Any]:
    return dict(value) if isinstance(value, Mapping) else {}


def _array_or_empty(value: object) -> list[Any]:
    return list(value) if isinstance(value, list) else []


def _add(errors: list[ManifestValidationError], code: str, path: str, message: str) -> None:
    errors.append(ManifestValidationError(code=code, path=path, message=message))


__all__ = [
    "ManifestValidationError",
    "ManifestValidationResult",
    "validate_manifest_json",
    "validate_manifest_object",
]
