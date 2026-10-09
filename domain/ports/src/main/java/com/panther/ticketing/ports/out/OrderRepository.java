package com.panther.ticketing.ports.out;

import com.panther.ticketing.model.order.Order;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface OrderRepository {
    Mono<Order> save(Order order);
    Mono<Order> findById(UUID id);
    Mono<Order> findByIdempotencyKey(String idempotencyKey);
    Mono<Order> updateStatusConditional(Order order);
}
