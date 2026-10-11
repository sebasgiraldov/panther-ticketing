package com.panther.ticketing.usecase;

import com.panther.ticketing.model.enums.OrderStatus;
import com.panther.ticketing.model.enums.ReservationStatus;
import com.panther.ticketing.model.exception.NotFoundException;
import com.panther.ticketing.model.order.Order;
import com.panther.ticketing.model.reservation.Reservation;
import com.panther.ticketing.model.exception.BusinessException;
import com.panther.ticketing.ports.in.IPurchaseTickets;
import com.panther.ticketing.ports.out.EventRepository;
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
    private final EventRepository eventRepository;
    private final TimeProvider timeProvider;

    public PurchaseTicketsUseCase(OrderRepository orderRepository,
                                   ReservationRepository reservationRepository,
                                   OrderQueueGateway orderQueueGateway,
                                   EventRepository eventRepository,
                                   TimeProvider timeProvider) {
        this.orderRepository = orderRepository;
        this.reservationRepository = reservationRepository;
        this.orderQueueGateway = orderQueueGateway;
        this.eventRepository = eventRepository;
        this.timeProvider = timeProvider;
    }

    @Override
    public Mono<Order> execute(UUID reservationId, String customerRef, String idempotencyKey) {
        // Effective key includes customerRef so two different customers cannot
        // collide on the same reservationId, and a malicious caller cannot retrieve
        // another customer's order by guessing a reservationId.
        String effectiveKey = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyKey + ":" + customerRef
                : reservationId.toString() + ":" + customerRef;

        return orderRepository.findByIdempotencyKey(effectiveKey)
                .flatMap(existing -> {
                    if (!existing.customerRef().equals(customerRef)) {
                        return Mono.error(new BusinessException("Order does not belong to customer: " + customerRef));
                    }
                    return Mono.just(existing);
                })
                .switchIfEmpty(Mono.defer(() -> createAndEnqueue(reservationId, customerRef, effectiveKey)));
    }

    private Mono<Order> createAndEnqueue(UUID reservationId, String customerRef, String idempotencyKey) {
        return reservationRepository.findById(reservationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Reservation not found: " + reservationId)))
                .flatMap(reservation -> {
                    if (!reservation.customerRef().equals(customerRef)) {
                        return Mono.error(new BusinessException(
                                "Reservation does not belong to customer: " + customerRef));
                    }
                    // A reservation can only be purchased while it is still ACTIVE. A
                    // reservation that was already finalized — purchased or marked as
                    // complimentary (CONFIRMED), or EXPIRED — must not be purchasable.
                    if (reservation.status() != ReservationStatus.ACTIVE) {
                        return Mono.error(new BusinessException(
                                "Reservation is not active (status: " + reservation.status() + "): " + reservationId));
                    }
                    if (reservation.isExpired(timeProvider.now())) {
                        return Mono.error(new BusinessException("Reservation has expired: " + reservationId));
                    }
                    int quantity = reservation.ticketIds().size();
                    return eventRepository.findTicketTypeById(reservation.ticketTypeId())
                            .switchIfEmpty(Mono.error(new NotFoundException(
                                    "TicketType not found: " + reservation.ticketTypeId())))
                            .map(ticketType -> {
                                BigDecimal totalAmount = ticketType.price()
                                        .multiply(BigDecimal.valueOf(quantity));
                                return new Order(
                                        UUID.randomUUID(), reservationId, reservation.ticketTypeId(),
                                        reservation.eventId(), customerRef, quantity,
                                        totalAmount, OrderStatus.PENDING, idempotencyKey,
                                        0L, timeProvider.now(), timeProvider.now());
                            });
                })
                .flatMap(orderRepository::save)
                .flatMap(order -> orderQueueGateway.enqueue(order)
                        .retryWhen(Retry.backoff(3, Duration.ofMillis(200)))
                        .thenReturn(order));
    }
}
