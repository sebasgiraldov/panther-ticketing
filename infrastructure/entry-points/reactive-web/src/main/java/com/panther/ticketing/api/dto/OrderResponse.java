package com.panther.ticketing.api.dto;

import com.panther.ticketing.model.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        UUID reservationId,
        String customerRef,
        int quantity,
        BigDecimal totalAmount,
        OrderStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
