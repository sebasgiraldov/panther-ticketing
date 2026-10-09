package com.panther.ticketing.usecase;

import com.panther.ticketing.model.enums.OrderStatus;
import com.panther.ticketing.model.exception.NotFoundException;
import com.panther.ticketing.model.order.Order;
import com.panther.ticketing.ports.in.IGetOrderStatus;
import com.panther.ticketing.ports.out.OrderRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public class GetOrderStatusUseCase implements IGetOrderStatus {

    private final OrderRepository orderRepository;

    public GetOrderStatusUseCase(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    public Mono<Order> execute(UUID orderId) {
        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.error(new NotFoundException("Order not found: " + orderId)));
    }
}
