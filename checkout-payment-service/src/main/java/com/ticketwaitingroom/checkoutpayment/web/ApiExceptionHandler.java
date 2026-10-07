package com.ticketwaitingroom.checkoutpayment.web;

import com.ticketwaitingroom.common.exceptions.ChargeInProgressException;
import com.ticketwaitingroom.common.exceptions.NotYoursException;
import com.ticketwaitingroom.common.exceptions.OrderNotFoundException;
import com.ticketwaitingroom.common.exceptions.OrderNotPayableException;
import com.ticketwaitingroom.common.exceptions.OrderNotRefundableException;
import com.ticketwaitingroom.common.exceptions.RefundInProgressException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain exceptions to HTTP responses. Charging an order that is already settled, or
 * one another attempt currently holds, are both expected outcomes of a retrying saga
 * rather than server faults — so they are 409s, and the saga can treat them as such.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    public ProblemDetail handleOrderNotFound(OrderNotFoundException ex) {
        return notFound(ex.getMessage());
    }

    /**
     * Answered identically to an unknown id, deliberately. A 403 would confirm the order
     * exists, which is enough to walk the id space and learn who bought what.
     */
    @ExceptionHandler(NotYoursException.class)
    public ProblemDetail handleNotYours(NotYoursException ex) {
        return notFound(ex.getMessage());
    }

    @ExceptionHandler(OrderNotPayableException.class)
    public ProblemDetail handleOrderNotPayable(OrderNotPayableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Order not payable");
        return problem;
    }

    @ExceptionHandler(ChargeInProgressException.class)
    public ProblemDetail handleChargeInProgress(ChargeInProgressException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Charge already in progress");
        return problem;
    }

    @ExceptionHandler(OrderNotRefundableException.class)
    public ProblemDetail handleOrderNotRefundable(OrderNotRefundableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Order not refundable");
        return problem;
    }

    @ExceptionHandler(RefundInProgressException.class)
    public ProblemDetail handleRefundInProgress(RefundInProgressException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Refund already in progress");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleInvalidRequest(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid request");
        return problem;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Illegal state transition");
        return problem;
    }

    private static ProblemDetail notFound(String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detail);
        problem.setTitle("Order not found");
        return problem;
    }
}
