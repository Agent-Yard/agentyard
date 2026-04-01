package com.lynxus.contracts.runtime;

import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public record TraceContext(String traceId, String spanId, String sampled) {
    private static final HexFormat HEX = HexFormat.of().withLowerCase();

    public TraceContext {
        traceId = normalize(traceId);
        spanId = normalize(spanId);
        sampled = normalize(sampled);
        if (!isHex(traceId, 32)) {
            throw new IllegalArgumentException("traceId must be 32 lowercase hex characters");
        }
        if (!isHex(spanId, 16)) {
            throw new IllegalArgumentException("spanId must be 16 lowercase hex characters");
        }
        if (!isHex(sampled, 2)) {
            throw new IllegalArgumentException("sampled must be 2 lowercase hex characters");
        }
    }

    public static TraceContext generate() {
        return new TraceContext(randomHex(32), randomHex(16), "01");
    }

    public static TraceContext fromTraceparent(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return generate();
        }
        String[] parts = traceparent.trim().split("-");
        if (parts.length != 4 || !isHex(parts[0], 2) || !isHex(parts[1], 32) || !isHex(parts[2], 16) || !isHex(parts[3], 2)) {
            return generate();
        }
        return new TraceContext(parts[1], randomHex(16), parts[3]);
    }

    public TraceContext nextSpan() {
        return new TraceContext(traceId, randomHex(16), sampled);
    }

    public String toTraceparent() {
        return "00-" + traceId + "-" + spanId + "-" + sampled;
    }

    private static boolean isHex(String candidate, int expectedLength) {
        return candidate != null
            && candidate.length() == expectedLength
            && candidate.chars().allMatch(character ->
                (character >= '0' && character <= '9') || (character >= 'a' && character <= 'f')
            );
    }

    private static String randomHex(int length) {
        byte[] bytes = new byte[length / 2];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
