package com.example.lynxus.extensiontemplate.protocol;

import com.lynxus.extension.sdk.generated.protocol.model.ExtensionError;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ExtensionExceptionHandler {
    @ExceptionHandler(ExtensionApiException.class)
    ResponseEntity<ExtensionError> handleExtensionApi(ExtensionApiException exception) {
        return ResponseEntity
            .status(exception.status())
            .body(extensionError(
                exception.errorCode(),
                exception.getMessage(),
                exception.category(),
                exception.retryable(),
                exception.details()
            ));
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentNotValidException.class,
        MissingRequestHeaderException.class,
        IllegalArgumentException.class
    })
    ResponseEntity<ExtensionError> handleBadRequest(Exception exception) {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(extensionError(
                "BAD_REQUEST",
                exception.getMessage(),
                ExtensionError.CategoryEnum.BAD_REQUEST,
                false,
                Map.of()
            ));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ExtensionError> handleUnexpected(Exception exception) {
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(extensionError(
                "EXTENSION_INTERNAL_ERROR",
                "extension service failed unexpectedly",
                ExtensionError.CategoryEnum.UNKNOWN,
                true,
                Map.of("exceptionType", exception.getClass().getSimpleName())
            ));
    }

    private static ExtensionError extensionError(
        String errorCode,
        String message,
        ExtensionError.CategoryEnum category,
        boolean retryable,
        Map<String, Object> details
    ) {
        return new ExtensionError()
            .errorCode(errorCode)
            .message(message)
            .category(category)
            .retryable(retryable)
            .details(details);
    }
}
