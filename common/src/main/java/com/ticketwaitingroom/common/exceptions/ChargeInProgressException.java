package com.ticketwaitingroom.common.exceptions;

public class ChargeInProgressException extends RuntimeException {

    public ChargeInProgressException(String message) {
        super(message);
    }
}
