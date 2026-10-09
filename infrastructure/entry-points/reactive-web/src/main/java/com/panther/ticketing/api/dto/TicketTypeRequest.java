package com.panther.ticketing.api.dto;

import java.math.BigDecimal;

public record TicketTypeRequest(
        String name,
        BigDecimal price,
        int totalQuantity
) {}
