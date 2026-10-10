package com.panther.ticketing.usecase;

import com.panther.ticketing.model.enums.OrderStatus;
import com.panther.ticketing.model.exception.NotFoundException;
import com.panther.ticketing.model.order.Order;
import com.panther.ticketing.ports.out.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetOrderStatusUseCaseTest {

    @Mock OrderRepository orderRepository;

    GetOrderStatusUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetOrderStatusUseCase(orderRepository);
    }

    @Test
    void execute_returnsOrder() {
        UUID orderId = UUID.randomUUID();
        Order order = new Order(orderId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "customer", 2, BigDecimal.valueOf(100), OrderStatus.CONFIRMED, "key", 0L,
                LocalDateTime.now(), LocalDateTime.now());
        when(orderRepository.findById(orderId)).thenReturn(Mono.just(order));

        StepVerifier.create(useCase.execute(orderId))
                .expectNext(order)
                .verifyComplete();
    }

    @Test
    void execute_notFound_throwsNotFoundException() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(orderId))
                .expectError(NotFoundException.class)
                .verify();
    }
}
