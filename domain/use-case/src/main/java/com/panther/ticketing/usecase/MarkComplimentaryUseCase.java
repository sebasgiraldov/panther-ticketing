package com.panther.ticketing.usecase;

import com.panther.ticketing.model.audit.AuditLog;
import com.panther.ticketing.model.enums.ReservationStatus;
import com.panther.ticketing.model.enums.TicketStatus;
import com.panther.ticketing.model.exception.BusinessException;
import com.panther.ticketing.model.exception.ConflictException;
import com.panther.ticketing.model.exception.DomainException;
import com.panther.ticketing.model.exception.NotFoundException;
import com.panther.ticketing.model.reservation.Reservation;
import com.panther.ticketing.model.ticket.Ticket;
import com.panther.ticketing.ports.in.IMarkComplimentary;
import com.panther.ticketing.ports.out.AuditGateway;
import com.panther.ticketing.ports.out.EventRepository;
import com.panther.ticketing.ports.out.ReservationRepository;
import com.panther.ticketing.ports.out.TicketRepository;
import com.panther.ticketing.ports.out.TimeProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Marks all tickets of an existing ACTIVE reservation as COMPLIMENTARY.
 *
 * <p>This is the alternate terminal action on a reservation (same reserve step as a
 * purchase, two endings: purchase OR complimentary). The reservation's tickets are
 * already RESERVED, so they transition RESERVED->COMPLIMENTARY and the inventory
 * counter only decrements reservedCount (soldCount and availableCount untouched —
 * COMPLIMENTARY is final and non-countable). The reservation is finalized to CONFIRMED.
 */
public class MarkComplimentaryUseCase implements IMarkComplimentary {

    private final ReservationRepository reservationRepository;
    private final TicketRepository ticketRepository;
    private final EventRepository eventRepository;
    private final AuditGateway auditGateway;
    private final TimeProvider timeProvider;

    public MarkComplimentaryUseCase(ReservationRepository reservationRepository,
                                     TicketRepository ticketRepository,
                                     EventRepository eventRepository,
                                     AuditGateway auditGateway,
                                     TimeProvider timeProvider) {
        this.reservationRepository = reservationRepository;
        this.ticketRepository = ticketRepository;
        this.eventRepository = eventRepository;
        this.auditGateway = auditGateway;
        this.timeProvider = timeProvider;
    }

    @Override
    public Mono<Reservation> execute(UUID eventId, UUID reservationId, String ownerRef) {
        return reservationRepository.findById(reservationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Reservation not found: " + reservationId)))
                .flatMap(reservation -> {
                    if (reservation.status() != ReservationStatus.ACTIVE
                            || reservation.isExpired(timeProvider.now())) {
                        return Mono.error(new BusinessException(
                                "Reservation is not ACTIVE or has expired: " + reservationId));
                    }
                    return complimentary(reservation, ownerRef);
                });
    }

    private Mono<Reservation> complimentary(Reservation reservation, String ownerRef) {
        return ticketRepository.findByReservationId(reservation.id())
                .collectList()
                .flatMap(tickets -> transitionTicketsToComplimentary(tickets, ownerRef)
                        .flatMap(marked -> eventRepository.findInventoryCounter(
                                        reservation.eventId(), reservation.ticketTypeId())
                                .flatMap(counter -> eventRepository.decrementReservedForComplimentary(
                                        reservation.eventId(), reservation.ticketTypeId(),
                                        marked.size(), counter.version()))
                                .thenReturn(marked)))
                .flatMap(marked -> reservationRepository.updateStatusConditional(
                                reservation.confirm(), ReservationStatus.ACTIVE)
                        .flatMap(confirmed -> auditGateway.record(new AuditLog(
                                        UUID.randomUUID(), "Reservation", confirmed.id(),
                                        "COMPLIMENTARY", "owner=" + ownerRef + " qty=" + marked.size(),
                                        timeProvider.now()))
                                .thenReturn(confirmed)));
    }

    private Mono<List<Ticket>> transitionTicketsToComplimentary(List<Ticket> tickets, String ownerRef) {
        return Flux.fromIterable(tickets)
                .flatMap(ticket -> applyComplimentary(ticket, ownerRef)
                        .flatMap(marked -> ticketRepository.updateStatusConditional(marked, TicketStatus.RESERVED)))
                .collectList();
    }

    // Pattern matching over the sealed DomainException hierarchy.
    private Mono<Ticket> applyComplimentary(Ticket ticket, String ownerRef) {
        try {
            return Mono.just(ticket.markComplimentary(ownerRef));
        } catch (DomainException ex) {
            return switch (ex) {
                case BusinessException e -> Mono.error(new BusinessException(
                        "Cannot mark ticket as complimentary: " + e.getMessage()));
                case NotFoundException e -> Mono.error(e);
                case ConflictException e -> Mono.error(e);
            };
        }
    }
}
