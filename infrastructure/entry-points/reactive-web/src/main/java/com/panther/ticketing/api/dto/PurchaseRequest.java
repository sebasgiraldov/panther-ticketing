package com.panther.ticketing.api.dto;

public record PurchaseRequest(
        String reservationId,
        String customerRef,
        String idempotencyKey
) {}
