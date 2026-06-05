package com.agentyard.channel.gateway.shared;

import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(NoSuchElementException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(problem(HttpStatus.NOT_FOUND, error.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ProblemDetail> handleConflict(ConflictException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(problem(HttpStatus.CONFLICT, error.getMessage()));
    }

    @ExceptionHandler(UnprocessableEntityException.class)
    public ResponseEntity<ProblemDetail> handleUnprocessableEntity(UnprocessableEntityException error) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
            .body(problem(HttpStatus.UNPROCESSABLE_CONTENT, error.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ProblemDetail> handleBadRequest(RuntimeException error) {
        return ResponseEntity.badRequest()
            .body(problem(HttpStatus.BAD_REQUEST, error.getMessage()));
    }

    private ProblemDetail problem(HttpStatus status, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail == null || detail.isBlank() ? status.getReasonPhrase() : detail);
        problemDetail.setTitle(status.getReasonPhrase());
        return problemDetail;
    }
}
