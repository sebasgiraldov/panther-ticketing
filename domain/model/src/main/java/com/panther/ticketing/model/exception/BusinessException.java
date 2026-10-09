package com.panther.ticketing.model.exception;

public final class BusinessException extends DomainException {
    public BusinessException(String message) {
        super(message);
    }
}
