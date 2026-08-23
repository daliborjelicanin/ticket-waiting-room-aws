package com.ticketwaitingroom.waitingroom.web;

import com.ticketwaitingroom.common.exceptions.UnknownQueueTokenException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain exceptions to HTTP responses. A token the room has never heard of — or one
 * that dropped out — is a 404 rather than a 5xx, since a client polling with a stale
 * token is an expected, benign outcome.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(UnknownQueueTokenException.class)
    public ProblemDetail handleUnknownToken(UnknownQueueTokenException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Unknown queue token");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleInvalidRequest(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid request");
        return problem;
    }
}
