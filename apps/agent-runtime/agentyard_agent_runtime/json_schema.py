from __future__ import annotations

from typing import Any


def validate_json_schema_value(value: Any, schema: dict[str, Any], path: str = "$") -> None:
    if not isinstance(schema, dict):
        return
    expected_type = schema.get("type")
    if isinstance(expected_type, list):
        if not any(_matches_type(value, item) for item in expected_type):
            raise ValueError(f"{path} expected type {expected_type}, got {type(value).__name__}")
    elif isinstance(expected_type, str) and not _matches_type(value, expected_type):
        raise ValueError(f"{path} expected type {expected_type}, got {type(value).__name__}")
    enum_values = schema.get("enum")
    if isinstance(enum_values, list) and value not in enum_values:
        raise ValueError(f"{path} must be one of {enum_values}")
    if isinstance(value, dict):
        _validate_object(value, schema, path)
        return
    if isinstance(value, list):
        _validate_array(value, schema, path)


def _validate_object(value: dict[str, Any], schema: dict[str, Any], path: str) -> None:
    required = schema.get("required")
    if isinstance(required, list):
        for key in required:
            if key not in value:
                raise ValueError(f"{path}.{key} is required")
    properties = schema.get("properties")
    if isinstance(properties, dict):
        for key, child_schema in properties.items():
            if key in value:
                validate_json_schema_value(value[key], child_schema, f"{path}.{key}")
        additional_properties = schema.get("additionalProperties", True)
        if additional_properties is False:
            unknown_keys = [key for key in value if key not in properties]
            if unknown_keys:
                raise ValueError(f"{path} contains unsupported keys {unknown_keys}")


def _validate_array(value: list[Any], schema: dict[str, Any], path: str) -> None:
    items = schema.get("items")
    if isinstance(items, dict):
        for index, item in enumerate(value):
            validate_json_schema_value(item, items, f"{path}[{index}]")


def _matches_type(value: Any, expected_type: str) -> bool:
    match expected_type:
        case "object":
            return isinstance(value, dict)
        case "array":
            return isinstance(value, list)
        case "string":
            return isinstance(value, str)
        case "integer":
            return isinstance(value, int) and not isinstance(value, bool)
        case "number":
            return isinstance(value, (int, float)) and not isinstance(value, bool)
        case "boolean":
            return isinstance(value, bool)
        case "null":
            return value is None
        case _:
            return True
