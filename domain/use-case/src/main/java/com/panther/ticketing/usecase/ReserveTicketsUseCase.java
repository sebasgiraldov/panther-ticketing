package com.panther.ticketing.usecase;

import com.panther.ticketing.model.audit.AuditLog;
import com.panther.ticketing.model.enums.TicketStatus;
import com.panther.ticketing.model.event.InventoryCounter;
import com.panther.ticketing.model.event.TicketType;
import com.panther.ticketing.model.exception.BusinessException;
import com.panther.ticketing.model.exception.ConflictException;
import com.panther.ticketing.model.reservation.Reservation;
import com.panther.ticketing.model.enums.ReservationStatus;
import com.panther.ticketing.model.ticket.Ticket;
import com.panther.ticketing.ports.in.IReserveTickets;
import com.panther.ticketing.ports.out.AuditGateway;
import com.panther.ticketing.ports.out.EventRepository;
import com.panther.ticketing.ports.out.ReservationRepository;
import com.panther.ticketing.ports.out.TicketRepository;
import com.panther.ticketing.ports.out.TimeProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public class ReserveTicketsUseCase implements IReserveTickets {

    private static final int RESERVATION_MINUTES = 10;
    private static final int MAX_RETRIES = 3;

    private final EventRepository eventRepository;
    private final TicketRepository ticketRepository;
    private final ReservationRepository reservationRepository;
    private final AuditGateway auditGateway;
    private final TimeProvider timeProvider;

    public ReserveTicketsUseCase(EventRepository eventRepository,
                                  TicketRepository ticketRepository,
                                  ReservationRepository reservationRepository,
                                  AuditGateway auditGateway,
                                  TimeProvider timeProvider) {
        this.eventRepository = eventRepository;
        this.ticketRepository = ticketRepository;
        this.reservationRepository = reservationRepository;
        this.auditGateway = auditGateway;
        this.timeProvider = timeProvider;
    }

    @Override
    public Mono<Reservation> execute(UUID eventId, UUID ticketTypeId, int quantity, String customerRef) {
        if (quantity <= 0) {
            return Mono.error(new BusinessException("Quantity must be positive"));
        }
        return eventRepository.findTicketTypeById(ticketTypeId)
                .switchIfEmpty(Mono.error(new BusinessException("TicketType not found: " + ticketTypeId)))
                .flatMap(ticketType -> reserveWithRetry(eventId, ticketTypeId, quantity, customerRef, ticketType))
                .retryWhen(Retry.backoff(MAX_RETRIES, Duration.ofMillis(100))
                        .filter(ex -> ex instanceof ConflictException));
    }

    private Mono<Reservation> reserveWithRetry(UUID eventId, UUID ticketTypeId, int quantity,
                                                String customerRef, TicketType ticketType) {
        return eventRepository.findInventoryCounter(eventId, ticketTypeId)
                .flatMap(counter -> {
                    if (counter.availableCount() < quantity) {
                        return Mono.error(new BusinessException("Not enough available tickets"));
                    }
                    return eventRepository.decrementAvailableIncrementReserved(eventId, ticketTypeId, quantity, counter.version());
                })
                .flatMap(updatedCounter -> reserveTickets(eventId, ticketTypeId, quantity, customerRef));
    }

    private Mono<Reservation> reserveTickets(UUID eventId, UUID ticketTypeId, int quantity, String customerRef) {
        UUID reservationId = UUID.randomUUID();
        return ticketRepository.findByEventIdAndTicketTypeIdAndStatus(eventId, ticketTypeId, TicketStatus.AVAILABLE, quantity)
                .collectList()
                .flatMap(available -> {
                    if (available.size() < quantity) {
                        return Mono.error(new ConflictException("Concurrent conflict: not enough AVAILABLE tickets"));
                    }
                    List<Ticket> toReserve = available.subList(0, quantity);
                    List<Ticket> reserved = toReserve.stream()
                            .map(t -> t.reserve(customerRef, reservationId))
                            .toList();
                    return ticketRepository.saveAll(reserved).collectList();
                })
                .flatMap(reservedTickets -> {
                    List<UUID> ticketIds = reservedTickets.stream().map(Ticket::id).toList();
                    Reservation reservation = new Reservation(
                            reservationId, eventId, ticketTypeId, ticketIds, customerRef,
                            timeProvider.now().plusMinutes(RESERVATION_MINUTES),
                            ReservationStatus.ACTIVE, 0L, timeProvider.now());
                    return reservationRepository.save(reservation);
                })
                .flatMap(reservation -> auditGateway.record(new AuditLog(
                        UUID.randomUUID(), "Reservation", reservation.id(),
                        "RESERVED", "qty=" + quantity + " customer=" + customerRef,
                        timeProvider.now())).thenReturn(reservation));
    }
}
