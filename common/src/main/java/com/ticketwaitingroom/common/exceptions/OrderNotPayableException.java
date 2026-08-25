package com.ticketwaitingroom.common.exceptions;

public class OrderNotPayableException extends RuntimeException {

    public OrderNotPayableException(String message) {
        super(message);
    }
}
