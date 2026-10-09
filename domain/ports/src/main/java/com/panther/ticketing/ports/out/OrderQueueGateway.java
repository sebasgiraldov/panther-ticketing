package com.panther.ticketing.ports.out;

import com.panther.ticketing.model.order.Order;
import reactor.core.publisher.Mono;

public interface OrderQueueGateway {
    Mono<Void> enqueue(Order order);
}
