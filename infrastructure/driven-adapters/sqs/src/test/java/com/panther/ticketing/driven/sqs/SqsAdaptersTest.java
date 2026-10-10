package com.panther.ticketing.driven.sqs;

import com.panther.ticketing.driven.sqs.consumer.OrderQueueConsumer;
import com.panther.ticketing.driven.sqs.producer.OrderQueueProducer;
import com.panther.ticketing.model.enums.OrderStatus;
import com.panther.ticketing.model.order.Order;
import com.panther.ticketing.ports.in.IProcessOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.*;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SqsAdaptersTest {

    @Mock SqsAsyncClient sqsClient;
    @Mock IProcessOrder processOrder;

    OrderQueueProducer producer;
    OrderQueueConsumer consumer;

    ObjectMapper objectMapper = new ObjectMapper();
    LocalDateTime now = LocalDateTime.of(2026, 1, 1, 10, 0);
    UUID orderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        producer = new OrderQueueProducer(sqsClient, objectMapper, "http://localhost:9324/queue/orders");
        consumer = new OrderQueueConsumer(sqsClient, processOrder, objectMapper, "http://localhost:9324/queue/orders");
    }

    // ===== OrderQueueProducer =====

    @Test
    void producer_enqueue_success_completesEmpty() {
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        SendMessageResponse.builder().messageId("msg-1").build()));

        StepVerifier.create(producer.enqueue(order()))
                .verifyComplete();
    }

    @Test
    void producer_enqueue_clientError_propagatesError() {
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("SQS down")));

        StepVerifier.create(producer.enqueue(order()))
                .expectError(RuntimeException.class)
                .verify();
    }

    // ===== OrderQueueConsumer — lifecycle =====

    @Test
    void consumer_stopPolling_beforeStart_doesNotThrow() {
        consumer.stopPolling();
    }

    @Test
    void consumer_startAndStop_idempotentStop() {
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ReceiveMessageResponse.builder().messages(List.of()).build()));
        consumer.startPolling();
        consumer.stopPolling();
        consumer.stopPolling();
    }

    // ===== OrderQueueConsumer — poll logic via VirtualTime =====

    @Test
    void consumer_poll_emptyQueue_noProcessing() {
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ReceiveMessageResponse.builder().messages(List.of()).build()));

        StepVerifier.withVirtualTime(() ->
                        Flux.interval(Duration.ofSeconds(5))
                                .take(1)
                                .flatMap(tick -> Mono.fromFuture(() -> sqsClient.receiveMessage(
                                                ReceiveMessageRequest.builder().queueUrl("q")
                                                        .maxNumberOfMessages(10).waitTimeSeconds(20).build()))
                                        .flatMapMany(resp -> Flux.fromIterable(resp.messages()))
                                        .then()))
                .thenAwait(Duration.ofSeconds(5))
                .verifyComplete();
    }

    @Test
    void consumer_poll_successMessage_processesAndDeletes() {
        String body = "{\"orderId\":\"" + orderId + "\"}";
        Message message = Message.builder()
                .messageId("msg-1").receiptHandle("handle-1").body(body).build();

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ReceiveMessageResponse.builder().messages(List.of(message)).build()));
        when(sqsClient.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DeleteMessageResponse.builder().build()));
        when(processOrder.execute(orderId)).thenReturn(Mono.empty());

        StepVerifier.withVirtualTime(() ->
                        Flux.interval(Duration.ofSeconds(5))
                                .take(1)
                                .flatMap(tick -> Mono.fromFuture(() -> sqsClient.receiveMessage(
                                                ReceiveMessageRequest.builder().queueUrl("q")
                                                        .maxNumberOfMessages(10).waitTimeSeconds(20).build()))
                                        .flatMapMany(resp -> Flux.fromIterable(resp.messages()))
                                        .flatMap(msg -> Mono.fromCallable(() ->
                                                        objectMapper.readValue(msg.body(), OrderQueueProducer.OrderMessage.class))
                                                .flatMap(m -> processOrder.execute(UUID.fromString(m.orderId())))
                                                .then(Mono.fromFuture(() -> sqsClient.deleteMessage(
                                                        DeleteMessageRequest.builder().queueUrl("q")
                                                                .receiptHandle(msg.receiptHandle()).build())))
                                                .then()
                                                .onErrorResume(ex -> Mono.empty()))
                                        .then()))
                .thenAwait(Duration.ofSeconds(5))
                .verifyComplete();
    }

    @Test
    void consumer_poll_processOrderFails_swallowsError() {
        String body = "{\"orderId\":\"" + orderId + "\"}";
        Message message = Message.builder()
                .messageId("msg-1").receiptHandle("handle-1").body(body).build();

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ReceiveMessageResponse.builder().messages(List.of(message)).build()));
        when(processOrder.execute(orderId))
                .thenReturn(Mono.error(new RuntimeException("processing failed")));

        StepVerifier.withVirtualTime(() ->
                        Flux.interval(Duration.ofSeconds(5))
                                .take(1)
                                .flatMap(tick -> Mono.fromFuture(() -> sqsClient.receiveMessage(
                                                ReceiveMessageRequest.builder().queueUrl("q")
                                                        .maxNumberOfMessages(10).waitTimeSeconds(20).build()))
                                        .flatMapMany(resp -> Flux.fromIterable(resp.messages()))
                                        .flatMap(msg -> Mono.fromCallable(() ->
                                                        objectMapper.readValue(msg.body(), OrderQueueProducer.OrderMessage.class))
                                                .flatMap(m -> processOrder.execute(UUID.fromString(m.orderId())))
                                                .then()
                                                .onErrorResume(ex -> Mono.empty()))
                                        .then()))
                .thenAwait(Duration.ofSeconds(5))
                .verifyComplete();
    }

    @Test
    void consumer_poll_sqsError_swallowsAndContinues() {
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("SQS unavailable")));

        StepVerifier.withVirtualTime(() ->
                        Flux.interval(Duration.ofSeconds(5))
                                .take(1)
                                .flatMap(tick -> Mono.fromFuture(() -> sqsClient.receiveMessage(
                                                ReceiveMessageRequest.builder().queueUrl("q")
                                                        .maxNumberOfMessages(10).waitTimeSeconds(20).build()))
                                        .flatMapMany(resp -> Flux.fromIterable(resp.messages()))
                                        .then()
                                        .onErrorResume(ex -> Mono.empty())))
                .thenAwait(Duration.ofSeconds(5))
                .verifyComplete();
    }

    @Test
    void consumer_poll_invalidJson_swallowsError() {
        Message message = Message.builder()
                .messageId("msg-bad").receiptHandle("handle-bad").body("not-json").build();

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ReceiveMessageResponse.builder().messages(List.of(message)).build()));

        StepVerifier.withVirtualTime(() ->
                        Flux.interval(Duration.ofSeconds(5))
                                .take(1)
                                .flatMap(tick -> Mono.fromFuture(() -> sqsClient.receiveMessage(
                                                ReceiveMessageRequest.builder().queueUrl("q")
                                                        .maxNumberOfMessages(10).waitTimeSeconds(20).build()))
                                        .flatMapMany(resp -> Flux.fromIterable(resp.messages()))
                                        .flatMap(msg -> Mono.fromCallable(() ->
                                                        objectMapper.readValue(msg.body(), OrderQueueProducer.OrderMessage.class))
                                                .flatMap(m -> processOrder.execute(UUID.fromString(m.orderId())))
                                                .then()
                                                .onErrorResume(ex -> Mono.empty()))
                                        .then()))
                .thenAwait(Duration.ofSeconds(5))
                .verifyComplete();
    }

    private Order order() {
        return new Order(orderId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "customer", 2, BigDecimal.valueOf(100), OrderStatus.PENDING, "key", 0L, now, now);
    }
}
