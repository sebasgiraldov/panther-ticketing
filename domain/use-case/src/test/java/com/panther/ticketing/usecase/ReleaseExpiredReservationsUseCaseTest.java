package com.panther.ticketing.usecase;

import com.panther.ticketing.model.audit.AuditLog;
import com.panther.ticketing.model.enums.ReservationStatus;
import com.panther.ticketing.model.enums.TicketStatus;
import com.panther.ticketing.model.event.InventoryCounter;
import com.panther.ticketing.model.reservation.Reservation;
import com.panther.ticketing.model.ticket.Ticket;
import com.panther.ticketing.ports.out.AuditGateway;
import com.panther.ticketing.ports.out.EventRepository;
import com.panther.ticketing.ports.out.ReservationRepository;
import com.panther.ticketing.ports.out.TicketRepository;
import com.panther.ticketing.ports.out.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReleaseExpiredReservationsUseCaseTest {

    @Mock ReservationRepository reservationRepository;
    @Mock TicketRepository ticketRepository;
    @Mock EventRepository eventRepository;
    @Mock AuditGateway auditGateway;
    @Mock TimeProvider timeProvider;

    ReleaseExpiredReservationsUseCase useCase;

    LocalDateTime now = LocalDateTime.of(2026, 1, 1, 10, 0);
    UUID eventId = UUID.randomUUID();
    UUID ttId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        useCase = new ReleaseExpiredReservationsUseCase(reservationRepository, ticketRepository, eventRepository, auditGateway, timeProvider);
        when(timeProvider.now()).thenReturn(now);
    }

    @Test
    void execute_noExpiredReservations_completesEmpty() {
        when(reservationRepository.findByStatusAndExpiresAtBefore(ReservationStatus.ACTIVE, now))
                .thenReturn(Flux.empty());

        StepVerifier.create(useCase.execute())
                .verifyComplete();
    }

    @Test
    void execute_releasesExpiredReservation() {
        UUID reservationId = UUID.randomUUID();
        Reservation active = new Reservation(reservationId, eventId, ttId, List.of(UUID.randomUUID()),
                "customer", now.minusMinutes(1), ReservationStatus.ACTIVE, 0L, now.minusMinutes(11));
        Reservation expired = active.expire();
        Ticket t1 = new Ticket(UUID.randomUUID(), eventId, ttId, TicketStatus.RESERVED,
                BigDecimal.valueOf(50), null, "customer", reservationId, 0L, now, now);
        Ticket released = t1.release();
        InventoryCounter counter = new InventoryCounter(eventId, ttId, 0, 1, 0, 0, 0L);
        InventoryCounter updated = new InventoryCounter(eventId, ttId, 1, 0, 0, 0, 1L);

        when(reservationRepository.findByStatusAndExpiresAtBefore(ReservationStatus.ACTIVE, now))
                .thenReturn(Flux.just(active));
        when(reservationRepository.updateStatusConditional(any(), eq(ReservationStatus.ACTIVE)))
                .thenReturn(Mono.just(expired));
        when(ticketRepository.findByReservationId(reservationId)).thenReturn(Flux.just(t1));
        when(ticketRepository.updateStatusConditional(any(), eq(TicketStatus.RESERVED))).thenReturn(Mono.just(released));
        when(eventRepository.findInventoryCounter(eventId, ttId)).thenReturn(Mono.just(counter));
        when(eventRepository.decrementReservedIncrementAvailable(eventId, ttId, 1, 0L)).thenReturn(Mono.just(updated));
        when(auditGateway.record(any(AuditLog.class))).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute())
                .verifyComplete();
    }

    @Test
    void execute_errorOnOneReservation_continuesWithOthers() {
        UUID reservationId = UUID.randomUUID();
        Reservation active = new Reservation(reservationId, eventId, ttId, List.of(),
                "customer", now.minusMinutes(1), ReservationStatus.ACTIVE, 0L, now);

        when(reservationRepository.findByStatusAndExpiresAtBefore(ReservationStatus.ACTIVE, now))
                .thenReturn(Flux.just(active));
        when(reservationRepository.updateStatusConditional(any(), eq(ReservationStatus.ACTIVE)))
                .thenReturn(Mono.error(new RuntimeException("DynamoDB error")));

        StepVerifier.create(useCase.execute())
                .verifyComplete();
    }
}
