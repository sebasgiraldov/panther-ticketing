package com.panther.ticketing.model.ticket;

import com.panther.ticketing.model.enums.TicketStatus;
import com.panther.ticketing.model.exception.BusinessException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Individual ticket entity. Identified by UUID.
 * State transitions are validated and return new immutable instances.
 * SOLD and COMPLIMENTARY are final states.
 */
public record Ticket(
        UUID id,
        UUID eventId,
        UUID ticketTypeId,
        TicketStatus status,
        BigDecimal price,
        String seatNumber,
        String ownerRef,
        UUID reservationId,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public Ticket reserve(String owner, UUID resId) {
        if (status != TicketStatus.AVAILABLE) {
            throw new BusinessException("Ticket " + id + " is not AVAILABLE (current: " + status + ")");
        }
        return new Ticket(id, eventId, ticketTypeId, TicketStatus.RESERVED, price,
                seatNumber, owner, resId, version, createdAt, LocalDateTime.now());
    }

    public Ticket markPendingConfirmation() {
        if (status != TicketStatus.RESERVED) {
            throw new BusinessException("Ticket " + id + " is not RESERVED (current: " + status + ")");
        }
        return new Ticket(id, eventId, ticketTypeId, TicketStatus.PENDING_CONFIRMATION, price,
                seatNumber, ownerRef, reservationId, version, createdAt, LocalDateTime.now());
    }

    public Ticket confirmSale() {
        if (status != TicketStatus.PENDING_CONFIRMATION) {
            throw new BusinessException("Ticket " + id + " is not PENDING_CONFIRMATION (current: " + status + ")");
        }
        return new Ticket(id, eventId, ticketTypeId, TicketStatus.SOLD, price,
                seatNumber, ownerRef, reservationId, version, createdAt, LocalDateTime.now());
    }

    public Ticket markComplimentary(String owner) {
        if (status != TicketStatus.AVAILABLE) {
            throw new BusinessException("Ticket " + id + " is not AVAILABLE for complimentary (current: " + status + ")");
        }
        return new Ticket(id, eventId, ticketTypeId, TicketStatus.COMPLIMENTARY, price,
                seatNumber, owner, null, version, createdAt, LocalDateTime.now());
    }

    public Ticket release() {
        if (status != TicketStatus.RESERVED) {
            throw new BusinessException("Ticket " + id + " is not RESERVED, cannot release (current: " + status + ")");
        }
        return new Ticket(id, eventId, ticketTypeId, TicketStatus.AVAILABLE, price,
                seatNumber, null, null, version, createdAt, LocalDateTime.now());
    }

    public boolean isFinal() {
        return status == TicketStatus.SOLD || status == TicketStatus.COMPLIMENTARY;
    }
}
