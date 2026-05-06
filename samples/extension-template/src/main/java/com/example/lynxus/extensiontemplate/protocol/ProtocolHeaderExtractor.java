package com.example.lynxus.extensiontemplate.protocol;

import com.lynxus.extension.sdk.protocol.LynxusExtensionHeaders;
import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class ProtocolHeaderExtractor {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    public ExtensionRequestContext descriptorContext(HttpServletRequest request) {
        String idempotencyKey = required(request, LynxusExtensionHeaders.IDEMPOTENCY_KEY);
        if (!IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw ExtensionApiException.badRequest("Idempotency-Key must match ^[A-Za-z0-9._:-]+$ and be at most 128 characters");
        }
        return new ExtensionRequestContext(
            required(request, LynxusExtensionHeaders.REGISTRATION_ID),
            required(request, LynxusExtensionHeaders.DESCRIPTOR_TYPE),
            required(request, LynxusExtensionHeaders.DESCRIPTOR_ID),
            required(request, LynxusExtensionHeaders.TRACE_ID),
            required(request, LynxusExtensionHeaders.REQUEST_ID),
            idempotencyKey
        );
    }

    public ExtensionRequestContext credentialContext(HttpServletRequest request) {
        return new ExtensionRequestContext(
            null,
            null,
            null,
            required(request, LynxusExtensionHeaders.TRACE_ID),
            required(request, LynxusExtensionHeaders.REQUEST_ID),
            null
        );
    }

    private static String required(HttpServletRequest request, String headerName) {
        String value = request.getHeader(headerName);
        if (value == null || value.isBlank()) {
            throw ExtensionApiException.badRequest(headerName + " header is required");
        }
        return value;
    }
}
