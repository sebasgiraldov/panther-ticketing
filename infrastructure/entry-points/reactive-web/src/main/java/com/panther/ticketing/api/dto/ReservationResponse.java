package com.panther.ticketing.api.dto;

import com.panther.ticketing.model.enums.ReservationStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ReservationResponse(
        UUID id,
        UUID eventId,
        UUID ticketTypeId,
        List<UUID> ticketIds,
        String customerRef,
        LocalDateTime expiresAt,
        ReservationStatus status
) {}
