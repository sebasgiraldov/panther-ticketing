package com.panther.ticketing.driven.sqs.consumer;

import com.panther.ticketing.driven.sqs.producer.OrderQueueProducer;
import com.panther.ticketing.ports.in.IProcessOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.*;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderQueueConsumerTest {

    @Mock SqsAsyncClient sqsClient;
    @Mock IProcessOrder processOrder;

    OrderQueueConsumer consumer;
    ObjectMapper objectMapper = new ObjectMapper();
    UUID orderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        consumer = new OrderQueueConsumer(sqsClient, processOrder, objectMapper,
                "http://localhost:9324/queue/orders");
    }

    // --- lifecycle ---

    @Test
    void stopPolling_beforeStart_doesNotThrow() {
        consumer.stopPolling();
    }

    @Test
    void startAndStop_idempotentStop() {
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ReceiveMessageResponse.builder().messages(List.of()).build()));
        consumer.startPolling();
        consumer.stopPolling();
        consumer.stopPolling();
    }

    // --- poll() ---

    @Test
    void poll_emptyQueue_completesEmpty() {
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ReceiveMessageResponse.builder().messages(List.of()).build()));

        StepVerifier.create(consumer.poll())
                .verifyComplete();
    }

    @Test
    void poll_sqsError_swallowsAndCompletes() {
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("SQS unavailable")));

        StepVerifier.create(consumer.poll())
                .verifyComplete();
    }

    @Test
    void poll_withMessage_processesIt() {
        String body = "{\"orderId\":\"" + orderId + "\"}";
        Message message = Message.builder()
                .messageId("msg-1").receiptHandle("handle-1").body(body).build();

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ReceiveMessageResponse.builder().messages(List.of(message)).build()));
        when(sqsClient.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DeleteMessageResponse.builder().build()));
        when(processOrder.execute(orderId)).thenReturn(Mono.empty());

        StepVerifier.create(consumer.poll())
                .verifyComplete();
    }

    // --- processMessage() ---

    @Test
    void processMessage_success_deletesAndCompletes() {
        String body = "{\"orderId\":\"" + orderId + "\"}";
        Message message = Message.builder()
                .messageId("msg-1").receiptHandle("handle-1").body(body).build();

        when(processOrder.execute(orderId)).thenReturn(Mono.empty());
        when(sqsClient.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DeleteMessageResponse.builder().build()));

        StepVerifier.create(consumer.processMessage(message))
                .verifyComplete();
    }

    @Test
    void processMessage_processOrderFails_swallowsError() {
        String body = "{\"orderId\":\"" + orderId + "\"}";
        Message message = Message.builder()
                .messageId("msg-1").receiptHandle("handle-1").body(body).build();

        when(processOrder.execute(orderId))
                .thenReturn(Mono.error(new RuntimeException("processing failed")));

        StepVerifier.create(consumer.processMessage(message))
                .verifyComplete();
    }

    @Test
    void processMessage_invalidJson_swallowsError() {
        Message message = Message.builder()
                .messageId("msg-bad").receiptHandle("handle-bad").body("not-json").build();

        StepVerifier.create(consumer.processMessage(message))
                .verifyComplete();
    }

    // --- deleteMessage() ---

    @Test
    void deleteMessage_success_completes() {
        Message message = Message.builder()
                .messageId("msg-1").receiptHandle("handle-1").body("{}").build();

        when(sqsClient.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DeleteMessageResponse.builder().build()));

        StepVerifier.create(consumer.deleteMessage(message))
                .verifyComplete();
    }
}
