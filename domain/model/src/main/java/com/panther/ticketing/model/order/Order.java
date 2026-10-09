package com.panther.ticketing.model.order;

import com.panther.ticketing.model.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record Order(
        UUID id,
        UUID reservationId,
        UUID ticketTypeId,
        UUID eventId,
        String customerRef,
        int quantity,
        BigDecimal totalAmount,
        OrderStatus status,
        String idempotencyKey,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public Order withStatus(OrderStatus newStatus) {
        return new Order(id, reservationId, ticketTypeId, eventId, customerRef, quantity,
                totalAmount, newStatus, idempotencyKey, version, createdAt, LocalDateTime.now());
    }
}
