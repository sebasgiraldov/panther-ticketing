package com.panther.ticketing.model.reservation;

import com.panther.ticketing.model.enums.ReservationStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record Reservation(
        UUID id,
        UUID eventId,
        UUID ticketTypeId,
        List<UUID> ticketIds,
        String customerRef,
        LocalDateTime expiresAt,
        ReservationStatus status,
        long version,
        LocalDateTime createdAt
) {
    public boolean isExpired(LocalDateTime now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    public Reservation expire() {
        return new Reservation(id, eventId, ticketTypeId, ticketIds, customerRef,
                expiresAt, ReservationStatus.EXPIRED, version, createdAt);
    }

    public Reservation confirm() {
        return new Reservation(id, eventId, ticketTypeId, ticketIds, customerRef,
                expiresAt, ReservationStatus.CONFIRMED, version, createdAt);
    }
}
