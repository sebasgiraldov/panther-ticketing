package com.panther.ticketing.model.exception;

public sealed abstract class DomainException extends RuntimeException
        permits BusinessException, ConflictException, NotFoundException {

    protected DomainException(String message) {
        super(message);
    }
}
