package com.panther.ticketing.usecase;

import com.panther.ticketing.model.enums.OrderStatus;
import com.panther.ticketing.model.enums.ReservationStatus;
import com.panther.ticketing.model.event.TicketType;
import com.panther.ticketing.model.exception.BusinessException;
import com.panther.ticketing.model.exception.NotFoundException;
import com.panther.ticketing.model.order.Order;
import com.panther.ticketing.model.reservation.Reservation;
import com.panther.ticketing.ports.out.EventRepository;
import com.panther.ticketing.ports.out.OrderQueueGateway;
import com.panther.ticketing.ports.out.OrderRepository;
import com.panther.ticketing.ports.out.ReservationRepository;
import com.panther.ticketing.ports.out.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PurchaseTicketsUseCaseTest {

    @Mock OrderRepository orderRepository;
    @Mock ReservationRepository reservationRepository;
    @Mock OrderQueueGateway orderQueueGateway;
    @Mock EventRepository eventRepository;
    @Mock TimeProvider timeProvider;

    PurchaseTicketsUseCase useCase;

    LocalDateTime now = LocalDateTime.of(2026, 1, 1, 10, 0);

    @BeforeEach
    void setUp() {
        useCase = new PurchaseTicketsUseCase(orderRepository, reservationRepository, orderQueueGateway, eventRepository, timeProvider);
        when(timeProvider.now()).thenReturn(now);
    }

    @Test
    void execute_idempotency_returnsExistingOrder() {
        UUID reservationId = UUID.randomUUID();
        Order existing = order(reservationId, OrderStatus.CONFIRMED);
        // effective key now includes customerRef
        when(orderRepository.findByIdempotencyKey("idem-key:customer")).thenReturn(Mono.just(existing));

        StepVerifier.create(useCase.execute(reservationId, "customer", "idem-key"))
                .expectNext(existing)
                .verifyComplete();
    }

    @Test
    void execute_idempotency_differentCustomer_throwsBusinessException() {
        UUID reservationId = UUID.randomUUID();
        Order existing = order(reservationId, OrderStatus.CONFIRMED); // customerRef = "customer"
        when(orderRepository.findByIdempotencyKey("idem-key:otro-cliente")).thenReturn(Mono.just(existing));

        StepVerifier.create(useCase.execute(reservationId, "otro-cliente", "idem-key"))
                .expectError(BusinessException.class)
                .verify();
    }

    @Test
    void execute_nullIdempotencyKey_usesReservationIdWithCustomerRef() {
        UUID reservationId = UUID.randomUUID();
        UUID ttId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Reservation reservation = activeReservation(reservationId, eventId, ttId, now.plusMinutes(10));
        TicketType tt = new TicketType(ttId, eventId, "General", BigDecimal.valueOf(50), 5);
        Order saved = order(reservationId, OrderStatus.PENDING);
        String expectedKey = reservationId + ":customer";

        when(orderRepository.findByIdempotencyKey(expectedKey)).thenReturn(Mono.empty());
        when(reservationRepository.findById(reservationId)).thenReturn(Mono.just(reservation));
        when(eventRepository.findTicketTypeById(ttId)).thenReturn(Mono.just(tt));
        when(orderRepository.save(any())).thenReturn(Mono.just(saved));
        when(orderQueueGateway.enqueue(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(reservationId, "customer", null))
                .expectNextMatches(o -> o.status() == OrderStatus.PENDING)
                .verifyComplete();
    }

    @Test
    void execute_reservationNotFound_throwsNotFoundException() {
        UUID reservationId = UUID.randomUUID();
        when(orderRepository.findByIdempotencyKey(any())).thenReturn(Mono.empty());
        when(reservationRepository.findById(reservationId)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(reservationId, "customer", "key"))
                .expectError(NotFoundException.class)
                .verify();
    }

    @Test
    void execute_wrongCustomerRef_throwsBusinessException() {
        UUID reservationId = UUID.randomUUID();
        UUID ttId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        // Reservation belongs to "customer", but request comes with "otro-cliente"
        Reservation reservation = activeReservation(reservationId, eventId, ttId, now.plusMinutes(10));

        when(orderRepository.findByIdempotencyKey(any())).thenReturn(Mono.empty());
        when(reservationRepository.findById(reservationId)).thenReturn(Mono.just(reservation));

        StepVerifier.create(useCase.execute(reservationId, "otro-cliente", null))
                .expectError(BusinessException.class)
                .verify();
    }

    @Test
    void execute_expiredReservation_throwsBusinessException() {
        UUID reservationId = UUID.randomUUID();
        UUID ttId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Reservation expired = activeReservation(reservationId, eventId, ttId, now.minusMinutes(1));

        when(orderRepository.findByIdempotencyKey(any())).thenReturn(Mono.empty());
        when(reservationRepository.findById(reservationId)).thenReturn(Mono.just(expired));

        StepVerifier.create(useCase.execute(reservationId, "customer", "key"))
                .expectError(BusinessException.class)
                .verify();
    }

    @Test
    void execute_ticketTypeNotFound_throwsNotFoundException() {
        UUID reservationId = UUID.randomUUID();
        UUID ttId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Reservation reservation = activeReservation(reservationId, eventId, ttId, now.plusMinutes(10));

        when(orderRepository.findByIdempotencyKey(any())).thenReturn(Mono.empty());
        when(reservationRepository.findById(reservationId)).thenReturn(Mono.just(reservation));
        when(eventRepository.findTicketTypeById(ttId)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(reservationId, "customer", "key"))
                .expectError(NotFoundException.class)
                .verify();
    }

    private Order order(UUID reservationId, OrderStatus status) {
        return new Order(UUID.randomUUID(), reservationId, UUID.randomUUID(), UUID.randomUUID(),
                "customer", 2, BigDecimal.valueOf(100), status, "key", 0L, now, now);
    }

    private Reservation activeReservation(UUID id, UUID eventId, UUID ttId, LocalDateTime expiresAt) {
        return new Reservation(id, eventId, ttId, List.of(UUID.randomUUID(), UUID.randomUUID()),
                "customer", expiresAt, ReservationStatus.ACTIVE, 0L, now);
    }
}
