package com.panther.ticketing.usecase;

import com.panther.ticketing.model.audit.AuditLog;
import com.panther.ticketing.model.enums.ReservationStatus;
import com.panther.ticketing.model.enums.TicketStatus;
import com.panther.ticketing.model.event.InventoryCounter;
import com.panther.ticketing.model.exception.BusinessException;
import com.panther.ticketing.model.exception.NotFoundException;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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
@MockitoSettings(strictness = Strictness.LENIENT)
class MarkComplimentaryUseCaseTest {

    @Mock ReservationRepository reservationRepository;
    @Mock TicketRepository ticketRepository;
    @Mock EventRepository eventRepository;
    @Mock AuditGateway auditGateway;
    @Mock TimeProvider timeProvider;

    MarkComplimentaryUseCase useCase;

    LocalDateTime now = LocalDateTime.of(2026, 1, 1, 10, 0);
    UUID eventId = UUID.randomUUID();
    UUID ttId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        useCase = new MarkComplimentaryUseCase(reservationRepository, ticketRepository, eventRepository, auditGateway, timeProvider);
        when(timeProvider.now()).thenReturn(now);
    }

    @Test
    void execute_happyPath_marksComplimentary() {
        UUID reservationId = UUID.randomUUID();
        Reservation reservation = activeReservation(reservationId, now.plusMinutes(10));
        Ticket t1 = reservedTicket(reservationId);
        Ticket complimentary = t1.markComplimentary("vip");
        InventoryCounter counter = new InventoryCounter(eventId, ttId, 8, 2, 0, 0, 0L);
        InventoryCounter updated = new InventoryCounter(eventId, ttId, 8, 0, 0, 1, 1L);
        Reservation confirmed = reservation.confirm();

        when(reservationRepository.findById(reservationId)).thenReturn(Mono.just(reservation));
        when(ticketRepository.findByReservationId(reservationId)).thenReturn(Flux.just(t1));
        when(ticketRepository.updateStatusConditional(any(), eq(TicketStatus.RESERVED))).thenReturn(Mono.just(complimentary));
        when(eventRepository.findInventoryCounter(eventId, ttId)).thenReturn(Mono.just(counter));
        when(eventRepository.decrementReservedForComplimentary(eventId, ttId, 1, 0L)).thenReturn(Mono.just(updated));
        when(reservationRepository.updateStatusConditional(any(), eq(ReservationStatus.ACTIVE))).thenReturn(Mono.just(confirmed));
        when(auditGateway.record(any(AuditLog.class))).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(eventId, reservationId, "vip"))
                .expectNextMatches(r -> r.status() == ReservationStatus.CONFIRMED)
                .verifyComplete();
    }

    @Test
    void execute_reservationNotFound_throwsNotFoundException() {
        UUID reservationId = UUID.randomUUID();
        when(reservationRepository.findById(reservationId)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(eventId, reservationId, "vip"))
                .expectError(NotFoundException.class)
                .verify();
    }

    @Test
    void execute_expiredReservation_throwsBusinessException() {
        UUID reservationId = UUID.randomUUID();
        Reservation expired = activeReservation(reservationId, now.minusMinutes(1));
        when(reservationRepository.findById(reservationId)).thenReturn(Mono.just(expired));

        StepVerifier.create(useCase.execute(eventId, reservationId, "vip"))
                .expectError(BusinessException.class)
                .verify();
    }

    @Test
    void execute_nonActiveReservation_throwsBusinessException() {
        UUID reservationId = UUID.randomUUID();
        Reservation confirmed = new Reservation(reservationId, eventId, ttId, List.of(),
                "customer", now.plusMinutes(10), ReservationStatus.CONFIRMED, 0L, now);
        when(reservationRepository.findById(reservationId)).thenReturn(Mono.just(confirmed));

        StepVerifier.create(useCase.execute(eventId, reservationId, "vip"))
                .expectError(BusinessException.class)
                .verify();
    }

    @Test
    void execute_ticketNotReserved_throwsBusinessException() {
        UUID reservationId = UUID.randomUUID();
        Reservation reservation = activeReservation(reservationId, now.plusMinutes(10));
        Ticket soldTicket = new Ticket(UUID.randomUUID(), eventId, ttId, TicketStatus.SOLD,
                BigDecimal.valueOf(50), null, "customer", reservationId, 0L, now, now);

        when(reservationRepository.findById(reservationId)).thenReturn(Mono.just(reservation));
        when(ticketRepository.findByReservationId(reservationId)).thenReturn(Flux.just(soldTicket));

        StepVerifier.create(useCase.execute(eventId, reservationId, "vip"))
                .expectError(BusinessException.class)
                .verify();
    }

    private Reservation activeReservation(UUID id, LocalDateTime expiresAt) {
        return new Reservation(id, eventId, ttId, List.of(UUID.randomUUID()),
                "customer", expiresAt, ReservationStatus.ACTIVE, 0L, now);
    }

    private Ticket reservedTicket(UUID reservationId) {
        return new Ticket(UUID.randomUUID(), eventId, ttId, TicketStatus.RESERVED,
                BigDecimal.valueOf(50), null, "customer", reservationId, 0L, now, now);
    }
}
