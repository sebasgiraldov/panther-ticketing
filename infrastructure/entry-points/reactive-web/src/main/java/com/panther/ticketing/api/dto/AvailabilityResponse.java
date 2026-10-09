package com.panther.ticketing.api.dto;

import java.util.UUID;

public record AvailabilityResponse(
        UUID eventId,
        UUID ticketTypeId,
        int availableCount,
        int reservedCount,
        int soldCount
) {}
