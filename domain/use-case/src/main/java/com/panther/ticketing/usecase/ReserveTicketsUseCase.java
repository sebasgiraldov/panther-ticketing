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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public class ReserveTicketsUseCase implements IReserveTickets {

    private static final Logger log = LoggerFactory.getLogger(ReserveTicketsUseCase.class);

    private final EventRepository eventRepository;
    private final TicketRepository ticketRepository;
    private final ReservationRepository reservationRepository;
    private final AuditGateway auditGateway;
    private final TimeProvider timeProvider;
    private final int reservationMinutes;
    private final int maxRetries;
    private final long retryBackoffMs;

    public ReserveTicketsUseCase(EventRepository eventRepository,
                                  TicketRepository ticketRepository,
                                  ReservationRepository reservationRepository,
                                  AuditGateway auditGateway,
                                  TimeProvider timeProvider,
                                  int reservationMinutes,
                                  int maxRetries,
                                  long retryBackoffMs) {
        this.eventRepository = eventRepository;
        this.ticketRepository = ticketRepository;
        this.reservationRepository = reservationRepository;
        this.auditGateway = auditGateway;
        this.timeProvider = timeProvider;
        this.reservationMinutes = reservationMinutes;
        this.maxRetries = maxRetries;
        this.retryBackoffMs = retryBackoffMs;
    }

    @Override
    public Mono<Reservation> execute(UUID eventId, UUID ticketTypeId, int quantity, String customerRef) {
        if (quantity <= 0) {
            return Mono.error(new BusinessException("Quantity must be positive"));
        }
        return eventRepository.findTicketTypeById(ticketTypeId)
                .switchIfEmpty(Mono.error(new BusinessException("TicketType not found: " + ticketTypeId)))
                .flatMap(ticketType -> Mono.defer(() -> reserveWithRetry(eventId, ticketTypeId, quantity, customerRef, ticketType))
                        .retryWhen(Retry.backoff(maxRetries, Duration.ofMillis(retryBackoffMs))
                                .filter(ex -> ex instanceof ConflictException && ex.getMessage().contains("Inventory conditional check"))));
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
                // The counter is now durably decremented. If ticket selection/persistence
                // fails after this point, compensate by releasing the counter (reserved -= qty,
                // available += qty), then propagate the ORIGINAL error. The compensation is
                // scoped to reserveTickets only, so guard failures above (not-enough /
                // "Inventory conditional check" conflicts) are never compensated and the
                // retry on "Inventory conditional check" still fires upstream.
                .flatMap(updatedCounter -> reserveTickets(eventId, ticketTypeId, quantity, customerRef)
                        .onErrorResume(err -> compensateReservation(eventId, ticketTypeId, quantity, err)));
    }

    /**
     * Compensating action for BUG 2: a reservation whose counter was already decremented but
     * whose ticket selection/persistence failed. Reads the FRESH counter version (a retry or
     * concurrent write may have bumped it) and performs the release conditional write, mirroring
     * the expiry path. If the compensation itself fails it is logged and swallowed so the ORIGINAL
     * error wins; the expiry scheduler reconciles any residue (same accepted trade-off as release).
     */
    private Mono<Reservation> compensateReservation(UUID eventId, UUID ticketTypeId, int quantity, Throwable originalError) {
        return eventRepository.findInventoryCounter(eventId, ticketTypeId)
                .flatMap(fresh -> eventRepository.decrementReservedIncrementAvailable(
                        eventId, ticketTypeId, quantity, fresh.version()))
                .onErrorResume(compErr -> {
                    log.warn("Failed to compensate counter after reservation failure for event={} ticketType={}: {}",
                            eventId, ticketTypeId, compErr.getMessage());
                    return Mono.empty();
                })
                .then(Mono.error(originalError));
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
                            timeProvider.now().plusMinutes(reservationMinutes),
                            ReservationStatus.ACTIVE, 0L, timeProvider.now());
                    return reservationRepository.save(reservation);
                })
                .flatMap(reservation -> auditGateway.record(new AuditLog(
                        UUID.randomUUID(), "Reservation", reservation.id(),
                        "RESERVED", "qty=" + quantity + " customer=" + customerRef,
                        timeProvider.now())).thenReturn(reservation));
    }
}
