package com.panther.ticketing.ports.in;

import com.panther.ticketing.model.order.Order;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface IGetOrderStatus {
    Mono<Order> execute(UUID orderId);
}
