package com.agentyard.contracts.http;

import java.net.URI;

public final class HttpUrls {
    private HttpUrls() {
    }

    public static URI join(String baseUrl, String path) {
        return URI.create(joinToString(baseUrl, path));
    }

    public static String joinToString(String baseUrl, String path) {
        return normalizeBase(baseUrl) + normalizePath(path);
    }

    private static String normalizeBase(String baseUrl) {
        String base = requireText(baseUrl, "baseUrl");
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static String normalizePath(String path) {
        String suffix = requireText(path, "path");
        return suffix.startsWith("/") ? suffix : "/" + suffix;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
