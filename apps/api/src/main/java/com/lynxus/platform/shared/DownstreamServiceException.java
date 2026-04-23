package com.lynxus.platform.shared;

import org.springframework.http.HttpStatusCode;

public class DownstreamServiceException extends RuntimeException {
    private final HttpStatusCode statusCode;

    public DownstreamServiceException(HttpStatusCode statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public HttpStatusCode statusCode() {
        return statusCode;
    }
}
