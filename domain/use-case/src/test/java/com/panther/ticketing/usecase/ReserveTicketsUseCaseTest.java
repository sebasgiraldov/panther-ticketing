package com.panther.ticketing.usecase;

import com.panther.ticketing.model.audit.AuditLog;
import com.panther.ticketing.model.enums.ReservationStatus;
import com.panther.ticketing.model.enums.TicketStatus;
import com.panther.ticketing.model.event.InventoryCounter;
import com.panther.ticketing.model.event.TicketType;
import com.panther.ticketing.model.exception.ConflictException;
import com.panther.ticketing.model.exception.BusinessException;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReserveTicketsUseCaseTest {

    @Mock EventRepository eventRepository;
    @Mock TicketRepository ticketRepository;
    @Mock ReservationRepository reservationRepository;
    @Mock AuditGateway auditGateway;
    @Mock TimeProvider timeProvider;

    ReserveTicketsUseCase useCase;

    UUID eventId = UUID.randomUUID();
    UUID ttId = UUID.randomUUID();
    LocalDateTime now = LocalDateTime.of(2026, 1, 1, 10, 0);

    @BeforeEach
    void setUp() {
        useCase = new ReserveTicketsUseCase(eventRepository, ticketRepository, reservationRepository,
                auditGateway, timeProvider, 10, 3, 100L);
        when(timeProvider.now()).thenReturn(now);
    }

    @Test
    void execute_happyPath_returnsReservation() {
        TicketType tt = new TicketType(ttId, eventId, "General", BigDecimal.valueOf(50), 5);
        InventoryCounter counter = new InventoryCounter(eventId, ttId, 5, 0, 0, 0, 0L);
        InventoryCounter updated = new InventoryCounter(eventId, ttId, 3, 2, 0, 0, 1L);
        Ticket t1 = availableTicket(eventId, ttId);
        Ticket t2 = availableTicket(eventId, ttId);
        Ticket reserved1 = t1.reserve("customer", UUID.randomUUID());
        Ticket reserved2 = t2.reserve("customer", UUID.randomUUID());
        Reservation reservation = new Reservation(UUID.randomUUID(), eventId, ttId,
                List.of(t1.id(), t2.id()), "customer", now.plusMinutes(10), ReservationStatus.ACTIVE, 0L, now);

        when(eventRepository.findTicketTypeById(ttId)).thenReturn(Mono.just(tt));
        when(eventRepository.findInventoryCounter(eventId, ttId)).thenReturn(Mono.just(counter));
        when(eventRepository.decrementAvailableIncrementReserved(eventId, ttId, 2, 0L)).thenReturn(Mono.just(updated));
        when(ticketRepository.findByEventIdAndTicketTypeIdAndStatus(eventId, ttId, TicketStatus.AVAILABLE, 2))
                .thenReturn(Flux.just(t1, t2));
        when(ticketRepository.saveAll(anyList())).thenReturn(Flux.just(reserved1, reserved2));
        when(reservationRepository.save(any())).thenReturn(Mono.just(reservation));
        when(auditGateway.record(any(AuditLog.class))).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(eventId, ttId, 2, "customer"))
                .expectNextMatches(r -> r.status() == ReservationStatus.ACTIVE)
                .verifyComplete();
    }

    @Test
    void execute_quantityZero_throwsBusinessException() {
        StepVerifier.create(useCase.execute(eventId, ttId, 0, "customer"))
                .expectError(BusinessException.class)
                .verify();
    }

    @Test
    void execute_ticketTypeNotFound_throwsBusinessException() {
        when(eventRepository.findTicketTypeById(ttId)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(eventId, ttId, 1, "customer"))
                .expectError(BusinessException.class)
                .verify();
    }

    @Test
    void execute_notEnoughAvailable_throwsBusinessException() {
        TicketType tt = new TicketType(ttId, eventId, "General", BigDecimal.valueOf(50), 5);
        InventoryCounter counter = new InventoryCounter(eventId, ttId, 1, 4, 0, 0, 0L);

        when(eventRepository.findTicketTypeById(ttId)).thenReturn(Mono.just(tt));
        when(eventRepository.findInventoryCounter(eventId, ttId)).thenReturn(Mono.just(counter));

        StepVerifier.create(useCase.execute(eventId, ttId, 3, "customer"))
                .expectError(BusinessException.class)
                .verify();
    }

    /**
     * Concurrency test: simulates two concurrent writers competing for the same inventory.
     * The first attempt hits a ConditionalCheckFailedException (translated to ConflictException
     * with "Inventory conditional check" in the message), triggering the retry. The second
     * attempt succeeds — verifying that the optimistic-locking retry loop works correctly.
     */
    @Test
    void execute_inventoryConflictOnFirstAttempt_retriesAndSucceeds() {
        TicketType tt = new TicketType(ttId, eventId, "General", BigDecimal.valueOf(50), 5);
        // Counter returned on both the failed attempt and the retry re-read
        InventoryCounter counter = new InventoryCounter(eventId, ttId, 5, 0, 0, 0, 0L);
        InventoryCounter updated = new InventoryCounter(eventId, ttId, 4, 1, 0, 0, 1L);
        Ticket t1 = availableTicket(eventId, ttId);
        Ticket reserved1 = t1.reserve("customer", UUID.randomUUID());
        Reservation reservation = new Reservation(UUID.randomUUID(), eventId, ttId,
                List.of(t1.id()), "customer", now.plusMinutes(10), ReservationStatus.ACTIVE, 0L, now);

        when(eventRepository.findTicketTypeById(ttId)).thenReturn(Mono.just(tt));
        // findInventoryCounter is called on every attempt (initial + retry)
        when(eventRepository.findInventoryCounter(eventId, ttId)).thenReturn(Mono.just(counter));
        // First call → conflict (simulates a concurrent writer winning the conditional write);
        // second call (retry) → success
        when(eventRepository.decrementAvailableIncrementReserved(eventId, ttId, 1, 0L))
                .thenReturn(
                        Mono.error(new ConflictException("Inventory conditional check failed")),
                        Mono.just(updated));
        when(ticketRepository.findByEventIdAndTicketTypeIdAndStatus(eventId, ttId, TicketStatus.AVAILABLE, 1))
                .thenReturn(Flux.just(t1));
        when(ticketRepository.saveAll(anyList())).thenReturn(Flux.just(reserved1));
        when(reservationRepository.save(any())).thenReturn(Mono.just(reservation));
        when(auditGateway.record(any(AuditLog.class))).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(eventId, ttId, 1, "customer"))
                .expectNextMatches(r -> r.status() == ReservationStatus.ACTIVE)
                .verifyComplete();
    }

    /**
     * Concurrency test: all retry attempts exhaust due to persistent inventory conflicts.
     * Verifies that after maxRetries the use case propagates the ConflictException
     * rather than looping forever.
     */
    @Test
    void execute_inventoryConflictExhaustsRetries_throwsConflictException() {
        TicketType tt = new TicketType(ttId, eventId, "General", BigDecimal.valueOf(50), 5);
        InventoryCounter counter = new InventoryCounter(eventId, ttId, 5, 0, 0, 0, 0L);

        when(eventRepository.findTicketTypeById(ttId)).thenReturn(Mono.just(tt));
        when(eventRepository.findInventoryCounter(eventId, ttId)).thenReturn(Mono.just(counter));
        // Always conflicts — exhausts all 3 retries
        when(eventRepository.decrementAvailableIncrementReserved(eventId, ttId, 1, 0L))
                .thenReturn(Mono.error(new ConflictException("Inventory conditional check failed")));

        StepVerifier.withVirtualTime(() -> useCase.execute(eventId, ttId, 1, "customer"))
                .thenAwait(java.time.Duration.ofSeconds(10))
                .expectError()
                .verify();
    }

    private Ticket availableTicket(UUID eventId, UUID ttId) {
        return new Ticket(UUID.randomUUID(), eventId, ttId, TicketStatus.AVAILABLE,
                BigDecimal.valueOf(50), null, null, null, 0L, now, now);
    }
}
