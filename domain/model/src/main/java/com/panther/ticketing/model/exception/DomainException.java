package com.panther.ticketing.model.exception;

public sealed class DomainException extends RuntimeException
        permits BusinessException, ConflictException, NotFoundException {

    protected DomainException(String message) {
        super(message);
    }
}
