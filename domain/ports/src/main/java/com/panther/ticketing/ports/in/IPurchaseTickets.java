package com.panther.ticketing.ports.in;

import com.panther.ticketing.model.order.Order;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface IPurchaseTickets {
    Mono<Order> execute(UUID reservationId, String customerRef, String idempotencyKey);
}
