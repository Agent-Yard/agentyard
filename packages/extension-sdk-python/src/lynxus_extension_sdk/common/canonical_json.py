"""Lynxus canonical JSON helper for descriptor and registration digests."""

from __future__ import annotations

from collections.abc import Mapping
from decimal import Decimal
from hashlib import sha256
from numbers import Number
from typing import Any

CANONICAL_JSON_DUPLICATE_KEY = "CANONICAL_JSON_DUPLICATE_KEY"
CANONICAL_JSON_INVALID_UNICODE = "CANONICAL_JSON_INVALID_UNICODE"
CANONICAL_JSON_UNSAFE_INTEGER = "CANONICAL_JSON_UNSAFE_INTEGER"
CANONICAL_JSON_UNSUPPORTED_NUMBER = "CANONICAL_JSON_UNSUPPORTED_NUMBER"
CANONICAL_JSON_UNSUPPORTED_VALUE = "CANONICAL_JSON_UNSUPPORTED_VALUE"

_SAFE_INTEGER_MAX = 9_007_199_254_740_991
_SAFE_INTEGER_MIN = -_SAFE_INTEGER_MAX


class CanonicalJsonError(ValueError):
    """Raised when input does not match the Lynxus canonical JSON profile."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


def canonicalize(value: Any) -> str:
    """Return canonical JSON.

    ``str`` input is treated as a raw JSON document so duplicate keys and
    number tokens can be rejected before decoding. Non-string input is treated
    as an already decoded JSON-profile value.
    """

    if isinstance(value, str):
        return _canonical_string(_parse_json_text(value))
    return _canonical_string(value)


def canonical_bytes(value: Any) -> bytes:
    return canonicalize(value).encode("utf-8")


def sha256_digest(value: Any) -> str:
    return f"sha256:{sha256(canonical_bytes(value)).hexdigest()}"


class _Parser:
    def __init__(self, raw: str) -> None:
        self.raw = raw
        self.index = 0

    def parse(self) -> Any:
        value = self._parse_value()
        self._skip_whitespace()
        if self.index != len(self.raw):
            raise CanonicalJsonError(CANONICAL_JSON_UNSUPPORTED_VALUE, "Unexpected trailing characters")
        return value

    def _parse_value(self) -> Any:
        self._skip_whitespace()
        if self.index >= len(self.raw):
            raise CanonicalJsonError(CANONICAL_JSON_UNSUPPORTED_VALUE, "Expected JSON value")
        char = self.raw[self.index]
        if char == "{":
            return self._parse_object()
        if char == "[":
            return self._parse_array()
        if char == '"':
            return self._parse_string()
        if char == "-" or "0" <= char <= "9":
            return self._parse_number()
        if self.raw.startswith("true", self.index):
            self.index += 4
            return True
        if self.raw.startswith("false", self.index):
            self.index += 5
            return False
        if self.raw.startswith("null", self.index):
            self.index += 4
            return None
        raise CanonicalJsonError(CANONICAL_JSON_UNSUPPORTED_VALUE, f"Unsupported JSON value at offset {self.index}")

    def _parse_object(self) -> dict[str, Any]:
        self.index += 1
        result: dict[str, Any] = {}
        seen: set[str] = set()
        self._skip_whitespace()
        if self._consume("}"):
            return result
        while self.index < len(self.raw):
            self._skip_whitespace()
            if self.index >= len(self.raw) or self.raw[self.index] != '"':
                raise CanonicalJsonError(CANONICAL_JSON_UNSUPPORTED_VALUE, "Object key must be a string")
            key = self._parse_string()
            if key in seen:
                raise CanonicalJsonError(CANONICAL_JSON_DUPLICATE_KEY, f"Duplicate object key {key}")
            seen.add(key)
            self._skip_whitespace()
            if not self._consume(":"):
                raise CanonicalJsonError(CANONICAL_JSON_UNSUPPORTED_VALUE, "Expected ':' after object key")
            result[key] = self._parse_value()
            self._skip_whitespace()
            if self._consume("}"):
                return result
            if not self._consume(","):
                raise CanonicalJsonError(CANONICAL_JSON_UNSUPPORTED_VALUE, "Expected ',' or '}' in object")
        raise CanonicalJsonError(CANONICAL_JSON_UNSUPPORTED_VALUE, "Unterminated object")

    def _parse_array(self) -> list[Any]:
        self.index += 1
        result: list[Any] = []
        self._skip_whitespace()
        if self._consume("]"):
            return result
        while self.index < len(self.raw):
            result.append(self._parse_value())
            self._skip_whitespace()
            if self._consume("]"):
                return result
            if not self._consume(","):
                raise CanonicalJsonError(CANONICAL_JSON_UNSUPPORTED_VALUE, "Expected ',' or ']' in array")
        raise CanonicalJsonError(CANONICAL_JSON_UNSUPPORTED_VALUE, "Unterminated array")

    def _parse_string(self) -> str:
        self.index += 1
        result: list[str] = []
        while self.index < len(self.raw):
            char = self.raw[self.index]
            code = ord(char)
            if char == '"':
                self.index += 1
                return "".join(result)
            if code <= 0x1F:
                raise CanonicalJsonError(CANONICAL_JSON_INVALID_UNICODE, "Unescaped control character in string")
            if char == "\\":
                result.append(self._parse_escape())
                continue
            if _is_high_surrogate(code):
                if self.index + 1 >= len(self.raw) or not _is_low_surrogate(ord(self.raw[self.index + 1])):
                    raise CanonicalJsonError(CANONICAL_JSON_INVALID_UNICODE, "Unpaired high surrogate in string")
                result.append(_surrogate_pair_to_char(code, ord(self.raw[self.index + 1])))
                self.index += 2
                continue
            if _is_low_surrogate(code):
                raise CanonicalJsonError(CANONICAL_JSON_INVALID_UNICODE, "Unpaired low surrogate in string")
            result.append(char)
            self.index += 1
        raise CanonicalJsonError(CANONICAL_JSON_INVALID_UNICODE, "Unterminated string")

    def _parse_escape(self) -> str:
        self.index += 1
        if self.index >= len(self.raw):
            raise CanonicalJsonError(CANONICAL_JSON_INVALID_UNICODE, "Unterminated escape")
        escaped = self.raw[self.index]
        self.index += 1
        if escaped in {'"', "\\", "/"}:
            return escaped
        if escaped == "b":
            return "\b"
        if escaped == "f":
            return "\f"
        if escaped == "n":
            return "\n"
        if escaped == "r":
            return "\r"
        if escaped == "t":
            return "\t"
        if escaped == "u":
            return self._parse_unicode_escape_after_u()
        raise CanonicalJsonError(CANONICAL_JSON_INVALID_UNICODE, f"Invalid escape \\{escaped}")

    def _parse_unicode_escape_after_u(self) -> str:
        code = self._read_hex_code_unit()
        if _is_high_surrogate(code):
            if self.index + 1 >= len(self.raw) or self.raw[self.index] != "\\" or self.raw[self.index + 1] != "u":
                raise CanonicalJsonError(
                    CANONICAL_JSON_INVALID_UNICODE,
                    "High surrogate must be followed by low surrogate escape",
                )
            self.index += 2
            low = self._read_hex_code_unit()
            if not _is_low_surrogate(low):
                raise CanonicalJsonError(CANONICAL_JSON_INVALID_UNICODE, "High surrogate not followed by low surrogate")
            return _surrogate_pair_to_char(code, low)
        if _is_low_surrogate(code):
            raise CanonicalJsonError(CANONICAL_JSON_INVALID_UNICODE, "Low surrogate without high surrogate")
        return chr(code)

    def _read_hex_code_unit(self) -> int:
        hex_value = self.raw[self.index : self.index + 4]
        if len(hex_value) != 4 or any(char not in "0123456789abcdefABCDEF" for char in hex_value):
            raise CanonicalJsonError(CANONICAL_JSON_INVALID_UNICODE, "Invalid unicode escape")
        self.index += 4
        return int(hex_value, 16)

    def _parse_number(self) -> int:
        start = self.index
        if self._consume("-") and self.index >= len(self.raw):
            raise CanonicalJsonError(CANONICAL_JSON_UNSUPPORTED_NUMBER, "Invalid number")
        if self._consume("0"):
            if self.index < len(self.raw) and _is_digit(self.raw[self.index]):
                raise CanonicalJsonError(CANONICAL_JSON_UNSUPPORTED_NUMBER, "Leading zero is not supported")
        elif self.index < len(self.raw) and "1" <= self.raw[self.index] <= "9":
            while self.index < len(self.raw) and _is_digit(self.raw[self.index]):
                self.index += 1
        else:
            raise CanonicalJsonError(CANONICAL_JSON_UNSUPPORTED_NUMBER, "Invalid number")
        if self.index < len(self.raw) and self.raw[self.index] in ".eE":
            raise CanonicalJsonError(CANONICAL_JSON_UNSUPPORTED_NUMBER, "Only JSON integers are supported")
        token = self.raw[start : self.index]
        if token == "-0":
            raise CanonicalJsonError(CANONICAL_JSON_UNSUPPORTED_NUMBER, "Negative zero is not supported")
        integer = int(token)
        if integer < _SAFE_INTEGER_MIN or integer > _SAFE_INTEGER_MAX:
            raise CanonicalJsonError(CANONICAL_JSON_UNSAFE_INTEGER, "Integer is outside the safe range")
        return integer

    def _consume(self, expected: str) -> bool:
        if self.index < len(self.raw) and self.raw[self.index] == expected:
            self.index += 1
            return True
        return False

    def _skip_whitespace(self) -> None:
        while self.index < len(self.raw) and self.raw[self.index] in " \t\n\r":
            self.index += 1


def _parse_json_text(raw: str) -> Any:
    return _Parser(raw).parse()


def _canonical_string(value: Any) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        if value < _SAFE_INTEGER_MIN or value > _SAFE_INTEGER_MAX:
            raise CanonicalJsonError(CANONICAL_JSON_UNSAFE_INTEGER, "Integer is outside the safe range")
        return str(value)
    if isinstance(value, float | Decimal):
        raise CanonicalJsonError(CANONICAL_JSON_UNSUPPORTED_NUMBER, "Only safe integers are supported")
    if isinstance(value, Number):
        raise CanonicalJsonError(CANONICAL_JSON_UNSUPPORTED_NUMBER, "Only safe integers are supported")
    if isinstance(value, str):
        return _quote_canonical_string(value)
    if isinstance(value, list):
        return "[" + ",".join(_canonical_string(item) for item in value) + "]"
    if isinstance(value, Mapping):
        keys = []
        for key in value:
            if not isinstance(key, str):
                raise CanonicalJsonError(CANONICAL_JSON_UNSUPPORTED_VALUE, "Object keys must be strings")
            keys.append(key)
        keys.sort(key=_utf16_sort_key)
        return "{" + ",".join(f"{_quote_canonical_string(key)}:{_canonical_string(value[key])}" for key in keys) + "}"
    raise CanonicalJsonError(CANONICAL_JSON_UNSUPPORTED_VALUE, f"Unsupported value type {type(value).__name__}")


def _quote_canonical_string(value: str) -> str:
    result = ['"']
    index = 0
    while index < len(value):
        char = value[index]
        code = ord(char)
        if char == '"':
            result.append('\\"')
        elif char == "\\":
            result.append("\\\\")
        elif char == "\b":
            result.append("\\b")
        elif char == "\t":
            result.append("\\t")
        elif char == "\n":
            result.append("\\n")
        elif char == "\f":
            result.append("\\f")
        elif char == "\r":
            result.append("\\r")
        elif code <= 0x1F:
            result.append(f"\\u{code:04x}")
        elif _is_high_surrogate(code):
            if index + 1 >= len(value) or not _is_low_surrogate(ord(value[index + 1])):
                raise CanonicalJsonError(CANONICAL_JSON_INVALID_UNICODE, "Unpaired high surrogate")
            result.append(_surrogate_pair_to_char(code, ord(value[index + 1])))
            index += 1
        elif _is_low_surrogate(code):
            raise CanonicalJsonError(CANONICAL_JSON_INVALID_UNICODE, "Unpaired low surrogate")
        else:
            result.append(char)
        index += 1
    result.append('"')
    return "".join(result)


def _utf16_sort_key(value: str) -> bytes:
    return value.encode("utf-16-be")


def _is_high_surrogate(code: int) -> bool:
    return 0xD800 <= code <= 0xDBFF


def _is_low_surrogate(code: int) -> bool:
    return 0xDC00 <= code <= 0xDFFF


def _surrogate_pair_to_char(high: int, low: int) -> str:
    code_point = 0x10000 + ((high - 0xD800) << 10) + (low - 0xDC00)
    return chr(code_point)


def _is_digit(value: str) -> bool:
    return "0" <= value <= "9"
