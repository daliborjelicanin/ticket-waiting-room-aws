package com.ticketwaitingroom.ticketissuance.web;

import com.ticketwaitingroom.common.exceptions.NotYoursException;
import com.ticketwaitingroom.common.exceptions.TicketNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(TicketNotFoundException.class)
    public ProblemDetail handleTicketNotFound(TicketNotFoundException ex) {
        return notFound(ex.getMessage());
    }

    /**
     * Answered identically to an unknown id, deliberately. A 403 here would confirm that
     * the ticket exists, which is all an attacker needs to walk the id space and learn who
     * bought what; the response must not distinguish "no such ticket" from "not yours".
     */
    @ExceptionHandler(NotYoursException.class)
    public ProblemDetail handleNotYours(NotYoursException ex) {
        return notFound(ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleInvalidRequest(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid request");
        return problem;
    }

    private static ProblemDetail notFound(String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detail);
        problem.setTitle("Ticket not found");
        return problem;
    }
}
