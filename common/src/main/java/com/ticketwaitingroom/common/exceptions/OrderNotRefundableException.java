package com.ticketwaitingroom.common.exceptions;

public class OrderNotRefundableException extends RuntimeException {

    public OrderNotRefundableException(String message) {
        super(message);
    }
}
