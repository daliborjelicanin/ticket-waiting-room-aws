package com.ticketwaitingroom.common.exceptions;

public class UnknownQueueTokenException extends RuntimeException {

    public UnknownQueueTokenException(String message) {
        super(message);
    }
}
