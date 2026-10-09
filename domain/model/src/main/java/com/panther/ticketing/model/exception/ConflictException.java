package com.panther.ticketing.model.exception;

public final class ConflictException extends DomainException {
    public ConflictException(String message) {
        super(message);
    }
}
