package com.panther.ticketing.model.event;

import java.math.BigDecimal;
import java.util.UUID;

public record TicketType(
        UUID id,
        UUID eventId,
        String name,
        BigDecimal price,
        int totalQuantity
) {}
