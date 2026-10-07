package com.ticketwaitingroom.common.exceptions;

public class RefundInProgressException extends RuntimeException {

    public RefundInProgressException(String message) {
        super(message);
    }
}
