package com.panther.ticketing.api.dto;

import com.panther.ticketing.model.enums.TicketStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record TicketResponse(
        UUID id,
        UUID eventId,
        UUID ticketTypeId,
        TicketStatus status,
        BigDecimal price,
        String seatNumber,
        String ownerRef
) {}
