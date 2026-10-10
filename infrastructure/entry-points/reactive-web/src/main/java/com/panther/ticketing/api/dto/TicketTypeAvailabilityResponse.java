package com.panther.ticketing.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record TicketTypeAvailabilityResponse(
        UUID id,
        String name,
        BigDecimal price,
        int totalQuantity,
        int availableCount,
        int reservedCount,
        int soldCount
) {}
