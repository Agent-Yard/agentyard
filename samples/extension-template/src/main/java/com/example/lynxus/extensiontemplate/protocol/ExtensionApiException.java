package com.example.lynxus.extensiontemplate.protocol;

import com.lynxus.extension.sdk.generated.protocol.model.ExtensionError;
import java.util.Map;
import org.springframework.http.HttpStatus;

public final class ExtensionApiException extends RuntimeException {
    private final HttpStatus status;
    private final String errorCode;
    private final ExtensionError.CategoryEnum category;
    private final boolean retryable;
    private final Map<String, Object> details;

    private ExtensionApiException(
        HttpStatus status,
        String errorCode,
        String message,
        ExtensionError.CategoryEnum category,
        boolean retryable,
        Map<String, Object> details
    ) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.category = category;
        this.retryable = retryable;
        this.details = Map.copyOf(details);
    }

    public static ExtensionApiException badRequest(String message) {
        return new ExtensionApiException(
            HttpStatus.BAD_REQUEST,
            "BAD_REQUEST",
            message,
            ExtensionError.CategoryEnum.BAD_REQUEST,
            false,
            Map.of()
        );
    }

    public static ExtensionApiException notImplemented(String operation) {
        return new ExtensionApiException(
            HttpStatus.NOT_IMPLEMENTED,
            "NOT_IMPLEMENTED",
            operation + " is a template placeholder and must be implemented before production use",
            ExtensionError.CategoryEnum.REMOTE_BUSINESS_REJECTED,
            false,
            Map.of("operation", operation)
        );
    }

    public HttpStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }

    public ExtensionError.CategoryEnum category() {
        return category;
    }

    public boolean retryable() {
        return retryable;
    }

    public Map<String, Object> details() {
        return details;
    }
}
