package com.panther.ticketing.model.exception;

public final class NotFoundException extends DomainException {
    public NotFoundException(String message) {
        super(message);
    }
}
