package com.panther.ticketing.usecase;

import com.panther.ticketing.model.enums.OrderStatus;
import com.panther.ticketing.model.exception.NotFoundException;
import com.panther.ticketing.model.order.Order;
import com.panther.ticketing.model.reservation.Reservation;
import com.panther.ticketing.model.exception.BusinessException;
import com.panther.ticketing.ports.in.IPurchaseTickets;
import com.panther.ticketing.ports.out.OrderQueueGateway;
import com.panther.ticketing.ports.out.OrderRepository;
import com.panther.ticketing.ports.out.ReservationRepository;
import com.panther.ticketing.ports.out.TimeProvider;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

public class PurchaseTicketsUseCase implements IPurchaseTickets {

    private final OrderRepository orderRepository;
    private final ReservationRepository reservationRepository;
    private final OrderQueueGateway orderQueueGateway;
    private final TimeProvider timeProvider;

    public PurchaseTicketsUseCase(OrderRepository orderRepository,
                                   ReservationRepository reservationRepository,
                                   OrderQueueGateway orderQueueGateway,
                                   TimeProvider timeProvider) {
        this.orderRepository = orderRepository;
        this.reservationRepository = reservationRepository;
        this.orderQueueGateway = orderQueueGateway;
        this.timeProvider = timeProvider;
    }

    @Override
    public Mono<Order> execute(UUID reservationId, String customerRef, String idempotencyKey) {
        // The idempotency key is optional on the request. When it is absent we fall
        // back to the reservationId, which gives one order per reservation and keeps
        // the idempotencyKey GSI key a non-null String (DynamoDB rejects a NULL key).
        String effectiveKey = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyKey
                : reservationId.toString();
        // Idempotency: return existing order if already created for this key
        return orderRepository.findByIdempotencyKey(effectiveKey)
                .switchIfEmpty(createAndEnqueue(reservationId, customerRef, effectiveKey));
    }

    private Mono<Order> createAndEnqueue(UUID reservationId, String customerRef, String idempotencyKey) {
        return reservationRepository.findById(reservationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Reservation not found: " + reservationId)))
                .flatMap(reservation -> {
                    if (reservation.isExpired(timeProvider.now())) {
                        return Mono.error(new BusinessException("Reservation has expired: " + reservationId));
                    }
                    Order order = new Order(
                            UUID.randomUUID(), reservationId, reservation.ticketTypeId(),
                            reservation.eventId(), customerRef, reservation.ticketIds().size(),
                            BigDecimal.ZERO, OrderStatus.PENDING, idempotencyKey,
                            0L, timeProvider.now(), timeProvider.now());
                    return orderRepository.save(order);
                })
                .flatMap(order -> orderQueueGateway.enqueue(order)
                        .retryWhen(Retry.backoff(3, Duration.ofMillis(200)))
                        .thenReturn(order));
    }
}
