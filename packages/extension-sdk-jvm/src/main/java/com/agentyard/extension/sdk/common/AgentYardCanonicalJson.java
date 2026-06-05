package com.agentyard.extension.sdk.common;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AgentYardCanonicalJson {
    private static final BigInteger SAFE_INTEGER_MAX = BigInteger.valueOf(9_007_199_254_740_991L);
    private static final BigInteger SAFE_INTEGER_MIN = SAFE_INTEGER_MAX.negate();

    private AgentYardCanonicalJson() {}

    public static String canonicalize(String rawJson) {
        return canonicalString(parse(rawJson));
    }

    public static byte[] canonicalBytes(String rawJson) {
        return canonicalize(rawJson).getBytes(StandardCharsets.UTF_8);
    }

    public static String sha256Digest(String rawJson) {
        return "sha256:" + HexFormat.of().formatHex(sha256(canonicalBytes(rawJson)));
    }

    public static String canonicalizeValue(Object value) {
        return canonicalString(value);
    }

    public static byte[] canonicalValueBytes(Object value) {
        return canonicalizeValue(value).getBytes(StandardCharsets.UTF_8);
    }

    public static String sha256ValueDigest(Object value) {
        return "sha256:" + HexFormat.of().formatHex(sha256(canonicalValueBytes(value)));
    }

    static Object parse(String rawJson) {
        return new Parser(rawJson).parse();
    }

    private static String canonicalString(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue ? "true" : "false";
        }
        if (value instanceof String stringValue) {
            return quoteCanonicalString(stringValue);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return checkedInteger(BigInteger.valueOf(((Number) value).longValue())).toString();
        }
        if (value instanceof BigInteger bigInteger) {
            return checkedInteger(bigInteger).toString();
        }
        if (value instanceof Number) {
            throw error(AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_UNSUPPORTED_NUMBER, "Only safe integers are supported");
        }
        if (value instanceof List<?> list) {
            StringBuilder result = new StringBuilder();
            result.append('[');
            for (int index = 0; index < list.size(); index += 1) {
                if (index > 0) {
                    result.append(',');
                }
                result.append(canonicalString(list.get(index)));
            }
            result.append(']');
            return result.toString();
        }
        if (value instanceof Map<?, ?> map) {
            List<String> keys = new ArrayList<>();
            for (Object key : map.keySet()) {
                if (!(key instanceof String stringKey)) {
                    throw error(AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_UNSUPPORTED_VALUE, "Object keys must be strings");
                }
                keys.add(stringKey);
            }
            keys.sort(Comparator.naturalOrder());
            StringBuilder result = new StringBuilder();
            result.append('{');
            for (int index = 0; index < keys.size(); index += 1) {
                if (index > 0) {
                    result.append(',');
                }
                String key = keys.get(index);
                result.append(quoteCanonicalString(key));
                result.append(':');
                result.append(canonicalString(map.get(key)));
            }
            result.append('}');
            return result.toString();
        }
        throw error(AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_UNSUPPORTED_VALUE, "Unsupported canonical JSON value");
    }

    private static BigInteger checkedInteger(BigInteger value) {
        if (value.compareTo(SAFE_INTEGER_MIN) < 0 || value.compareTo(SAFE_INTEGER_MAX) > 0) {
            throw error(AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_UNSAFE_INTEGER, "Integer is outside the safe range");
        }
        return value;
    }

    private static String quoteCanonicalString(String value) {
        StringBuilder result = new StringBuilder();
        result.append('"');
        for (int index = 0; index < value.length(); index += 1) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\t' -> result.append("\\t");
                case '\n' -> result.append("\\n");
                case '\f' -> result.append("\\f");
                case '\r' -> result.append("\\r");
                default -> {
                    if (current <= 0x1f) {
                        result.append("\\u");
                        result.append("%04x".formatted((int) current));
                    } else if (isHighSurrogate(current)) {
                        if (index + 1 >= value.length() || !isLowSurrogate(value.charAt(index + 1))) {
                            throw error(
                                AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_INVALID_UNICODE,
                                "Unpaired high surrogate"
                            );
                        }
                        result.append(current);
                        result.append(value.charAt(index + 1));
                        index += 1;
                    } else if (isLowSurrogate(current)) {
                        throw error(AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_INVALID_UNICODE, "Unpaired low surrogate");
                    } else {
                        result.append(current);
                    }
                }
            }
        }
        result.append('"');
        return result.toString();
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean isHighSurrogate(int codeUnit) {
        return codeUnit >= 0xd800 && codeUnit <= 0xdbff;
    }

    private static boolean isLowSurrogate(int codeUnit) {
        return codeUnit >= 0xdc00 && codeUnit <= 0xdfff;
    }

    private static AgentYardCanonicalJsonException error(AgentYardCanonicalJsonErrorCode code, String message) {
        return new AgentYardCanonicalJsonException(code, message);
    }

    private static final class Parser {
        private final String raw;
        private int index;

        private Parser(String raw) {
            if (raw == null) {
                throw error(AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_UNSUPPORTED_VALUE, "JSON input must not be null");
            }
            this.raw = raw;
        }

        private Object parse() {
            Object value = parseValue();
            skipWhitespace();
            if (index != raw.length()) {
                throw error(
                    AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_UNSUPPORTED_VALUE,
                    "Unexpected trailing characters"
                );
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (index >= raw.length()) {
                throw error(AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_UNSUPPORTED_VALUE, "Expected JSON value");
            }
            char current = current();
            if (current == '{') {
                return parseObject();
            }
            if (current == '[') {
                return parseArray();
            }
            if (current == '"') {
                return parseString();
            }
            if (current == '-' || isDigit(current)) {
                return parseNumber();
            }
            if (raw.startsWith("true", index)) {
                index += 4;
                return Boolean.TRUE;
            }
            if (raw.startsWith("false", index)) {
                index += 5;
                return Boolean.FALSE;
            }
            if (raw.startsWith("null", index)) {
                index += 4;
                return null;
            }
            throw error(
                AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_UNSUPPORTED_VALUE,
                "Unsupported JSON value at offset " + index
            );
        }

        private Map<String, Object> parseObject() {
            index += 1;
            Map<String, Object> object = new LinkedHashMap<>();
            Set<String> seenKeys = new HashSet<>();
            skipWhitespace();
            if (consume('}')) {
                return object;
            }
            while (index < raw.length()) {
                skipWhitespace();
                if (index >= raw.length() || current() != '"') {
                    throw error(AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_UNSUPPORTED_VALUE, "Object key must be a string");
                }
                String key = parseString();
                if (!seenKeys.add(key)) {
                    throw error(AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_DUPLICATE_KEY, "Duplicate object key " + key);
                }
                skipWhitespace();
                if (!consume(':')) {
                    throw error(AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_UNSUPPORTED_VALUE, "Expected ':' after object key");
                }
                object.put(key, parseValue());
                skipWhitespace();
                if (consume('}')) {
                    return object;
                }
                if (!consume(',')) {
                    throw error(AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_UNSUPPORTED_VALUE, "Expected ',' or '}' in object");
                }
            }
            throw error(AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_UNSUPPORTED_VALUE, "Unterminated object");
        }

        private List<Object> parseArray() {
            index += 1;
            List<Object> array = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) {
                return array;
            }
            while (index < raw.length()) {
                array.add(parseValue());
                skipWhitespace();
                if (consume(']')) {
                    return array;
                }
                if (!consume(',')) {
                    throw error(AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_UNSUPPORTED_VALUE, "Expected ',' or ']' in array");
                }
            }
            throw error(AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_UNSUPPORTED_VALUE, "Unterminated array");
        }

        private String parseString() {
            index += 1;
            StringBuilder result = new StringBuilder();
            while (index < raw.length()) {
                char current = current();
                if (current == '"') {
                    index += 1;
                    return result.toString();
                }
                if (current <= 0x1f) {
                    throw error(
                        AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_INVALID_UNICODE,
                        "Unescaped control character in string"
                    );
                }
                if (current == '\\') {
                    result.append(parseEscape());
                    continue;
                }
                if (isHighSurrogate(current)) {
                    if (index + 1 >= raw.length() || !isLowSurrogate(raw.charAt(index + 1))) {
                        throw error(
                            AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_INVALID_UNICODE,
                            "Unpaired high surrogate in string"
                        );
                    }
                    result.append(current);
                    result.append(raw.charAt(index + 1));
                    index += 2;
                    continue;
                }
                if (isLowSurrogate(current)) {
                    throw error(AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_INVALID_UNICODE, "Unpaired low surrogate in string");
                }
                result.append(current);
                index += 1;
            }
            throw error(AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_INVALID_UNICODE, "Unterminated string");
        }

        private String parseEscape() {
            index += 1;
            if (index >= raw.length()) {
                throw error(AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_INVALID_UNICODE, "Unterminated escape");
            }
            char escaped = current();
            index += 1;
            return switch (escaped) {
                case '"', '\\', '/' -> Character.toString(escaped);
                case 'b' -> "\b";
                case 'f' -> "\f";
                case 'n' -> "\n";
                case 'r' -> "\r";
                case 't' -> "\t";
                case 'u' -> parseUnicodeEscapeAfterU();
                default -> throw error(AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_INVALID_UNICODE, "Invalid escape");
            };
        }

        private String parseUnicodeEscapeAfterU() {
            char codeUnit = readHexCodeUnit();
            if (isHighSurrogate(codeUnit)) {
                if (index + 1 >= raw.length() || raw.charAt(index) != '\\' || raw.charAt(index + 1) != 'u') {
                    throw error(
                        AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_INVALID_UNICODE,
                        "High surrogate must be followed by low surrogate escape"
                    );
                }
                index += 2;
                char low = readHexCodeUnit();
                if (!isLowSurrogate(low)) {
                    throw error(
                        AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_INVALID_UNICODE,
                        "High surrogate not followed by low surrogate"
                    );
                }
                return new String(new char[] {codeUnit, low});
            }
            if (isLowSurrogate(codeUnit)) {
                throw error(AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_INVALID_UNICODE, "Low surrogate without high surrogate");
            }
            return Character.toString(codeUnit);
        }

        private char readHexCodeUnit() {
            if (index + 4 > raw.length()) {
                throw error(AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_INVALID_UNICODE, "Invalid unicode escape");
            }
            int value = 0;
            for (int offset = 0; offset < 4; offset += 1) {
                char current = raw.charAt(index + offset);
                int digit = Character.digit(current, 16);
                if (digit < 0) {
                    throw error(AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_INVALID_UNICODE, "Invalid unicode escape");
                }
                value = (value << 4) + digit;
            }
            index += 4;
            return (char) value;
        }

        private Long parseNumber() {
            int start = index;
            if (consume('-') && index >= raw.length()) {
                throw error(AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_UNSUPPORTED_NUMBER, "Invalid number");
            }
            if (consume('0')) {
                if (index < raw.length() && isDigit(current())) {
                    throw error(AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_UNSUPPORTED_NUMBER, "Leading zero is not supported");
                }
            } else if (index < raw.length() && isNonZeroDigit(current())) {
                while (index < raw.length() && isDigit(current())) {
                    index += 1;
                }
            } else {
                throw error(AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_UNSUPPORTED_NUMBER, "Invalid number");
            }
            if (index < raw.length() && (current() == '.' || current() == 'e' || current() == 'E')) {
                throw error(AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_UNSUPPORTED_NUMBER, "Only JSON integers are supported");
            }
            String token = raw.substring(start, index);
            if ("-0".equals(token)) {
                throw error(AgentYardCanonicalJsonErrorCode.CANONICAL_JSON_UNSUPPORTED_NUMBER, "Negative zero is not supported");
            }
            BigInteger integer = new BigInteger(token);
            checkedInteger(integer);
            return integer.longValueExact();
        }

        private boolean consume(char expected) {
            if (index < raw.length() && raw.charAt(index) == expected) {
                index += 1;
                return true;
            }
            return false;
        }

        private char current() {
            return raw.charAt(index);
        }

        private void skipWhitespace() {
            while (index < raw.length()) {
                char current = raw.charAt(index);
                if (current != ' ' && current != '\t' && current != '\n' && current != '\r') {
                    return;
                }
                index += 1;
            }
        }

        private static boolean isDigit(char value) {
            return value >= '0' && value <= '9';
        }

        private static boolean isNonZeroDigit(char value) {
            return value >= '1' && value <= '9';
        }
    }
}
