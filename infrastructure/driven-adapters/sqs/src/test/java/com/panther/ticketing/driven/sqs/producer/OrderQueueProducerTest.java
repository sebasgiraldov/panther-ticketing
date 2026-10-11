package com.panther.ticketing.driven.sqs.producer;

import com.panther.ticketing.model.enums.OrderStatus;
import com.panther.ticketing.model.order.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderQueueProducerTest {

    @Mock SqsAsyncClient sqsClient;

    OrderQueueProducer producer;
    LocalDateTime now = LocalDateTime.of(2026, 1, 1, 10, 0);
    UUID orderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        producer = new OrderQueueProducer(sqsClient, new ObjectMapper(), "http://localhost:9324/queue/orders");
    }

    @Test
    void enqueue_success_completesEmpty() {
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        SendMessageResponse.builder().messageId("msg-1").build()));

        StepVerifier.create(producer.enqueue(order()))
                .verifyComplete();
    }

    @Test
    void enqueue_clientError_propagatesError() {
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("SQS down")));

        StepVerifier.create(producer.enqueue(order()))
                .expectError(RuntimeException.class)
                .verify();
    }

    private Order order() {
        return new Order(orderId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "customer", 2, BigDecimal.valueOf(100), OrderStatus.PENDING, "key", 0L, now, now);
    }
}
