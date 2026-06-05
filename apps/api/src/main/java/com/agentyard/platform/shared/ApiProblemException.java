package com.agentyard.platform.shared;

import org.springframework.http.HttpStatusCode;

public class ApiProblemException extends RuntimeException {
    private final HttpStatusCode statusCode;
    private final String code;

    public ApiProblemException(HttpStatusCode statusCode, String code, String detail) {
        super(detail);
        this.statusCode = statusCode;
        this.code = code;
    }

    public HttpStatusCode statusCode() {
        return statusCode;
    }

    public String code() {
        return code;
    }
}
