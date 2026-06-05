package com.agentyard.extension.sdk.protocol;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsonDocuments {
    private JsonDocuments() {}

    public static Object parse(String rawJson) {
        return new Parser(rawJson).parse();
    }

    public static Map<String, Object> parseObject(String rawJson) {
        Object value = parse(rawJson);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("JSON document must be an object");
        }
        return stringObjectMap(map);
    }

    public static Map<String, Object> asObject(Object value, String path) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return stringObjectMap(map);
    }

    public static List<Object> asArray(Object value, String path) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(path + " must be an array");
        }
        return new ArrayList<>(list);
    }

    private static Map<String, Object> stringObjectMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("JSON object keys must be strings");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static final class Parser {
        private final String raw;
        private int index;

        private Parser(String raw) {
            if (raw == null) {
                throw new IllegalArgumentException("JSON input must not be null");
            }
            this.raw = raw;
        }

        private Object parse() {
            Object value = parseValue();
            skipWhitespace();
            if (index != raw.length()) {
                throw new IllegalArgumentException("Unexpected trailing JSON characters");
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (index >= raw.length()) {
                throw new IllegalArgumentException("Expected JSON value");
            }
            char current = raw.charAt(index);
            if (current == '{') {
                return parseObjectValue();
            }
            if (current == '[') {
                return parseArrayValue();
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
            throw new IllegalArgumentException("Unsupported JSON value at offset " + index);
        }

        private Map<String, Object> parseObjectValue() {
            index += 1;
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (consume('}')) {
                return result;
            }
            while (index < raw.length()) {
                skipWhitespace();
                if (index >= raw.length() || raw.charAt(index) != '"') {
                    throw new IllegalArgumentException("Object key must be a string");
                }
                String key = parseString();
                skipWhitespace();
                if (!consume(':')) {
                    throw new IllegalArgumentException("Expected ':' after object key");
                }
                result.put(key, parseValue());
                skipWhitespace();
                if (consume('}')) {
                    return result;
                }
                if (!consume(',')) {
                    throw new IllegalArgumentException("Expected ',' or '}' in object");
                }
            }
            throw new IllegalArgumentException("Unterminated object");
        }

        private List<Object> parseArrayValue() {
            index += 1;
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) {
                return result;
            }
            while (index < raw.length()) {
                result.add(parseValue());
                skipWhitespace();
                if (consume(']')) {
                    return result;
                }
                if (!consume(',')) {
                    throw new IllegalArgumentException("Expected ',' or ']' in array");
                }
            }
            throw new IllegalArgumentException("Unterminated array");
        }

        private String parseString() {
            index += 1;
            StringBuilder result = new StringBuilder();
            while (index < raw.length()) {
                char current = raw.charAt(index);
                if (current == '"') {
                    index += 1;
                    return result.toString();
                }
                if (current <= 0x1f) {
                    throw new IllegalArgumentException("Unescaped control character in string");
                }
                if (current == '\\') {
                    result.append(parseEscape());
                    continue;
                }
                result.append(current);
                index += 1;
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        private String parseEscape() {
            index += 1;
            if (index >= raw.length()) {
                throw new IllegalArgumentException("Unterminated escape");
            }
            char escaped = raw.charAt(index);
            index += 1;
            return switch (escaped) {
                case '"', '\\', '/' -> Character.toString(escaped);
                case 'b' -> "\b";
                case 'f' -> "\f";
                case 'n' -> "\n";
                case 'r' -> "\r";
                case 't' -> "\t";
                case 'u' -> Character.toString(readHexCodeUnit());
                default -> throw new IllegalArgumentException("Invalid escape");
            };
        }

        private char readHexCodeUnit() {
            if (index + 4 > raw.length()) {
                throw new IllegalArgumentException("Invalid unicode escape");
            }
            int value = 0;
            for (int offset = 0; offset < 4; offset += 1) {
                int digit = Character.digit(raw.charAt(index + offset), 16);
                if (digit < 0) {
                    throw new IllegalArgumentException("Invalid unicode escape");
                }
                value = (value << 4) + digit;
            }
            index += 4;
            return (char) value;
        }

        private Number parseNumber() {
            int start = index;
            if (consume('-') && index >= raw.length()) {
                throw new IllegalArgumentException("Invalid number");
            }
            if (consume('0')) {
                if (index < raw.length() && isDigit(raw.charAt(index))) {
                    throw new IllegalArgumentException("Leading zero is not valid JSON");
                }
            } else if (index < raw.length() && isNonZeroDigit(raw.charAt(index))) {
                while (index < raw.length() && isDigit(raw.charAt(index))) {
                    index += 1;
                }
            } else {
                throw new IllegalArgumentException("Invalid number");
            }

            boolean decimal = false;
            if (index < raw.length() && raw.charAt(index) == '.') {
                decimal = true;
                index += 1;
                if (index >= raw.length() || !isDigit(raw.charAt(index))) {
                    throw new IllegalArgumentException("Invalid fraction");
                }
                while (index < raw.length() && isDigit(raw.charAt(index))) {
                    index += 1;
                }
            }
            if (index < raw.length() && (raw.charAt(index) == 'e' || raw.charAt(index) == 'E')) {
                decimal = true;
                index += 1;
                if (index < raw.length() && (raw.charAt(index) == '+' || raw.charAt(index) == '-')) {
                    index += 1;
                }
                if (index >= raw.length() || !isDigit(raw.charAt(index))) {
                    throw new IllegalArgumentException("Invalid exponent");
                }
                while (index < raw.length() && isDigit(raw.charAt(index))) {
                    index += 1;
                }
            }

            String token = raw.substring(start, index);
            if (decimal) {
                return new BigDecimal(token);
            }
            try {
                return Long.parseLong(token);
            } catch (NumberFormatException exception) {
                return new BigInteger(token);
            }
        }

        private boolean consume(char expected) {
            if (index < raw.length() && raw.charAt(index) == expected) {
                index += 1;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (index < raw.length() && switch (raw.charAt(index)) {
                case ' ', '\t', '\n', '\r' -> true;
                default -> false;
            }) {
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
