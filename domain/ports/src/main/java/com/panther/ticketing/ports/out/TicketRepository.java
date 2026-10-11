package com.panther.ticketing.ports.out;

import com.panther.ticketing.model.enums.TicketStatus;
import com.panther.ticketing.model.ticket.Ticket;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

public interface TicketRepository {
    Mono<Ticket> save(Ticket ticket);
    Flux<Ticket> saveAll(List<Ticket> tickets);
    Mono<Ticket> findById(UUID id);
    Flux<Ticket> findByEventIdAndTicketTypeIdAndStatus(UUID eventId, UUID ticketTypeId, TicketStatus status, int limit);
    Flux<Ticket> findByReservationId(UUID reservationId);
    /**
     * Conditional write: updates status only if current status == expectedStatus AND version == ticket.version().
     * Throws ConflictException if condition fails.
     */
    Mono<Ticket> updateStatusConditional(Ticket ticket, TicketStatus expectedStatus);
}
