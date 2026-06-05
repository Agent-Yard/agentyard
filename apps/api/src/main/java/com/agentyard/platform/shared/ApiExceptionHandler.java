package com.agentyard.platform.shared;

import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
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

    @ExceptionHandler(DownstreamServiceException.class)
    public ResponseEntity<ProblemDetail> handleDownstreamService(DownstreamServiceException error) {
        return ResponseEntity.status(error.statusCode())
            .body(problem(error.statusCode(), error.getMessage()));
    }

    @ExceptionHandler(ApiProblemException.class)
    public ResponseEntity<ProblemDetail> handleApiProblem(ApiProblemException error) {
        ProblemDetail problem = problem(error.statusCode(), error.getMessage());
        problem.setProperty("code", error.code());
        return ResponseEntity.status(error.statusCode()).body(problem);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ProblemDetail> handleBadRequest(RuntimeException error) {
        return ResponseEntity.badRequest()
            .body(problem(HttpStatus.BAD_REQUEST, error.getMessage()));
    }

    private ProblemDetail problem(HttpStatusCode status, String detail) {
        HttpStatus resolvedStatus = HttpStatus.resolve(status.value());
        String resolvedDetail = detail == null || detail.isBlank()
            ? resolvedStatus == null ? "HTTP " + status.value() : resolvedStatus.getReasonPhrase()
            : detail;
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, resolvedDetail);
        problemDetail.setTitle(resolvedStatus == null ? "HTTP " + status.value() : resolvedStatus.getReasonPhrase());
        return problemDetail;
    }
}
