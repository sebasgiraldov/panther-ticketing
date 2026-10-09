package com.panther.ticketing.usecase;

import com.panther.ticketing.model.audit.AuditLog;
import com.panther.ticketing.model.enums.ReservationStatus;
import com.panther.ticketing.model.enums.TicketStatus;
import com.panther.ticketing.ports.in.IReleaseExpiredReservations;
import com.panther.ticketing.ports.out.AuditGateway;
import com.panther.ticketing.ports.out.EventRepository;
import com.panther.ticketing.ports.out.ReservationRepository;
import com.panther.ticketing.ports.out.TicketRepository;
import com.panther.ticketing.ports.out.TimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

public class ReleaseExpiredReservationsUseCase implements IReleaseExpiredReservations {

    private static final Logger log = LoggerFactory.getLogger(ReleaseExpiredReservationsUseCase.class);

    private final ReservationRepository reservationRepository;
    private final TicketRepository ticketRepository;
    private final EventRepository eventRepository;
    private final AuditGateway auditGateway;
    private final TimeProvider timeProvider;

    public ReleaseExpiredReservationsUseCase(ReservationRepository reservationRepository,
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
    public Mono<Void> execute() {
        return reservationRepository.findByStatusAndExpiresAtBefore(ReservationStatus.ACTIVE, timeProvider.now())
                .flatMap(reservation -> reservationRepository
                        .updateStatusConditional(reservation.expire(), ReservationStatus.ACTIVE)
                        .flatMap(expired -> ticketRepository.findByReservationId(expired.id())
                                .flatMap(ticket -> ticketRepository.updateStatusConditional(
                                        ticket.release(), TicketStatus.RESERVED))
                                .collectList()
                                .flatMap(released -> eventRepository
                                        .findInventoryCounter(expired.eventId(), expired.ticketTypeId())
                                        .flatMap(counter -> eventRepository.decrementReservedIncrementAvailable(
                                                expired.eventId(), expired.ticketTypeId(),
                                                released.size(), counter.version())))
                                .flatMap(counter -> auditGateway.record(new AuditLog(
                                        UUID.randomUUID(), "Reservation", expired.id(),
                                        "EXPIRED", "auto-released by scheduler", timeProvider.now()))))
                        .onErrorResume(ex -> {
                            log.warn("Failed to release reservation {}: {}", reservation.id(), ex.getMessage());
                            return Mono.empty();
                        }))
                .then();
    }
}
