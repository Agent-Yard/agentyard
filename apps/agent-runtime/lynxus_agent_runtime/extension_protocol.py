from __future__ import annotations

from lynxus_extension_sdk.common import (
    CanonicalJsonError,
    canonical_bytes,
    canonicalize,
    sha256_digest,
)
from lynxus_extension_sdk.protocol import (
    ExtensionError,
    ExtensionErrorCategory,
    ExtensionErrorParseError,
    parse_extension_error,
    parse_extension_error_json,
    parse_extension_error_object,
    parse_non_2xx_extension_error,
)
from lynxus_extension_sdk.validation import (
    ManifestValidationResult,
    default_protocol_schema_dir,
    validate_manifest_json,
    validate_manifest_object,
)


def validate_manifest_against_protocol_schema(manifest: object) -> ManifestValidationResult:
    return validate_manifest_object(manifest, schema_dir=default_protocol_schema_dir())


def validate_manifest_json_against_protocol_schema(raw_json: str) -> ManifestValidationResult:
    return validate_manifest_json(raw_json, schema_dir=default_protocol_schema_dir())


__all__ = [
    "CanonicalJsonError",
    "ExtensionError",
    "ExtensionErrorCategory",
    "ExtensionErrorParseError",
    "ManifestValidationResult",
    "canonical_bytes",
    "canonicalize",
    "default_protocol_schema_dir",
    "parse_extension_error",
    "parse_extension_error_json",
    "parse_extension_error_object",
    "parse_non_2xx_extension_error",
    "sha256_digest",
    "validate_manifest_against_protocol_schema",
    "validate_manifest_json",
    "validate_manifest_json_against_protocol_schema",
    "validate_manifest_object",
]
