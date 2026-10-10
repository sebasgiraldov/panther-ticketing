package com.panther.ticketing.usecase;

import com.panther.ticketing.model.audit.AuditLog;
import com.panther.ticketing.model.enums.OrderStatus;
import com.panther.ticketing.model.enums.ReservationStatus;
import com.panther.ticketing.model.enums.TicketStatus;
import com.panther.ticketing.model.event.InventoryCounter;
import com.panther.ticketing.model.exception.NotFoundException;
import com.panther.ticketing.model.order.Order;
import com.panther.ticketing.model.reservation.Reservation;
import com.panther.ticketing.model.ticket.Ticket;
import com.panther.ticketing.ports.out.AuditGateway;
import com.panther.ticketing.ports.out.EventRepository;
import com.panther.ticketing.ports.out.OrderRepository;
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
class ProcessOrderUseCaseTest {

    @Mock OrderRepository orderRepository;
    @Mock ReservationRepository reservationRepository;
    @Mock TicketRepository ticketRepository;
    @Mock EventRepository eventRepository;
    @Mock AuditGateway auditGateway;
    @Mock TimeProvider timeProvider;

    ProcessOrderUseCase useCase;

    LocalDateTime now = LocalDateTime.of(2026, 1, 1, 10, 0);
    UUID eventId = UUID.randomUUID();
    UUID ttId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        useCase = new ProcessOrderUseCase(orderRepository, reservationRepository, ticketRepository, eventRepository, auditGateway, timeProvider);
        when(timeProvider.now()).thenReturn(now);
    }

    @Test
    void execute_orderNotFound_throwsNotFoundException() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(orderId))
                .expectError(NotFoundException.class)
                .verify();
    }

    @Test
    void execute_alreadyConfirmed_skips() {
        UUID orderId = UUID.randomUUID();
        Order order = order(orderId, OrderStatus.CONFIRMED);
        when(orderRepository.findById(orderId)).thenReturn(Mono.just(order));

        StepVerifier.create(useCase.execute(orderId))
                .verifyComplete();
    }

    @Test
    void execute_alreadyFailed_skips() {
        UUID orderId = UUID.randomUUID();
        Order order = order(orderId, OrderStatus.FAILED);
        when(orderRepository.findById(orderId)).thenReturn(Mono.just(order));

        StepVerifier.create(useCase.execute(orderId))
                .verifyComplete();
    }

    @Test
    void execute_alreadyProcessing_skips() {
        UUID orderId = UUID.randomUUID();
        Order order = order(orderId, OrderStatus.PROCESSING);
        when(orderRepository.findById(orderId)).thenReturn(Mono.just(order));

        StepVerifier.create(useCase.execute(orderId))
                .verifyComplete();
    }

    @Test
    void execute_happyPath_confirmsOrder() {
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        Order pending = orderWithReservation(orderId, reservationId, OrderStatus.PENDING);
        Order processing = orderWithReservation(orderId, reservationId, OrderStatus.PROCESSING);
        Order confirmed = orderWithReservation(orderId, reservationId, OrderStatus.CONFIRMED);
        Reservation reservation = activeReservation(reservationId, now.plusMinutes(10));
        Ticket t1 = reservedTicket(reservationId);
        Ticket t2 = reservedTicket(reservationId);
        Ticket pending1 = t1.markPendingConfirmation();
        Ticket pending2 = t2.markPendingConfirmation();
        Ticket sold1 = pending1.confirmSale();
        Ticket sold2 = pending2.confirmSale();
        InventoryCounter counter = new InventoryCounter(eventId, ttId, 0, 2, 0, 0L);
        InventoryCounter updated = new InventoryCounter(eventId, ttId, 0, 0, 2, 1L);
        Reservation confirmedRes = reservation.confirm();

        when(orderRepository.findById(orderId)).thenReturn(Mono.just(pending));
        when(orderRepository.updateStatusConditional(any())).thenReturn(Mono.just(processing), Mono.just(confirmed));
        when(reservationRepository.findById(reservationId)).thenReturn(Mono.just(reservation));
        when(ticketRepository.findByReservationId(reservationId)).thenReturn(Flux.just(t1, t2));
        when(ticketRepository.updateStatusConditional(any(), eq(TicketStatus.RESERVED))).thenReturn(Mono.just(pending1), Mono.just(pending2));
        when(ticketRepository.updateStatusConditional(any(), eq(TicketStatus.PENDING_CONFIRMATION))).thenReturn(Mono.just(sold1), Mono.just(sold2));
        when(eventRepository.findInventoryCounter(eventId, ttId)).thenReturn(Mono.just(counter));
        when(eventRepository.decrementReservedIncrementSold(eventId, ttId, 2, 0L)).thenReturn(Mono.just(updated));
        when(reservationRepository.updateStatusConditional(any(), eq(ReservationStatus.ACTIVE))).thenReturn(Mono.just(confirmedRes));
        when(auditGateway.record(any(AuditLog.class))).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(orderId))
                .verifyComplete();
    }

    @Test
    void execute_expiredReservation_failsOrder() {
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        Order pending = orderWithReservation(orderId, reservationId, OrderStatus.PENDING);
        Order processing = orderWithReservation(orderId, reservationId, OrderStatus.PROCESSING);
        Order failed = orderWithReservation(orderId, reservationId, OrderStatus.FAILED);
        Reservation expired = activeReservation(reservationId, now.minusMinutes(1));
        Ticket t1 = reservedTicket(reservationId);
        InventoryCounter counter = new InventoryCounter(eventId, ttId, 0, 1, 0, 0L);
        InventoryCounter updated = new InventoryCounter(eventId, ttId, 1, 0, 0, 1L);
        Reservation expiredRes = expired.expire();

        when(orderRepository.findById(orderId)).thenReturn(Mono.just(pending));
        when(orderRepository.updateStatusConditional(any())).thenReturn(Mono.just(processing), Mono.just(failed));
        when(reservationRepository.findById(reservationId)).thenReturn(Mono.just(expired));
        when(auditGateway.record(any(AuditLog.class))).thenReturn(Mono.empty());
        when(ticketRepository.findByReservationId(reservationId)).thenReturn(Flux.just(t1));
        when(ticketRepository.updateStatusConditional(any(), eq(TicketStatus.RESERVED))).thenReturn(Mono.just(t1.release()));
        when(eventRepository.findInventoryCounter(eventId, ttId)).thenReturn(Mono.just(counter));
        when(eventRepository.decrementReservedIncrementAvailable(eventId, ttId, 1, 0L)).thenReturn(Mono.just(updated));
        when(reservationRepository.updateStatusConditional(any(), eq(ReservationStatus.ACTIVE))).thenReturn(Mono.just(expiredRes));

        StepVerifier.create(useCase.execute(orderId))
                .verifyComplete();
    }

    private Order order(UUID id, OrderStatus status) {
        return new Order(id, UUID.randomUUID(), ttId, eventId, "customer", 2,
                BigDecimal.valueOf(100), status, "key", 0L, now, now);
    }

    private Order orderWithReservation(UUID id, UUID reservationId, OrderStatus status) {
        return new Order(id, reservationId, ttId, eventId, "customer", 2,
                BigDecimal.valueOf(100), status, "key", 0L, now, now);
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
