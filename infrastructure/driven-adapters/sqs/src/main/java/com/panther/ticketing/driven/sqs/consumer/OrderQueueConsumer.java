package com.panther.ticketing.driven.sqs.consumer;

import tools.jackson.databind.ObjectMapper;
import com.panther.ticketing.driven.sqs.producer.OrderQueueProducer.OrderMessage;
import com.panther.ticketing.ports.in.IProcessOrder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.time.Duration;
import java.util.UUID;

@Component
public class OrderQueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderQueueConsumer.class);

    private final SqsAsyncClient sqsClient;
    private final IProcessOrder processOrder;
    private final ObjectMapper objectMapper;
    private final String queueUrl;
    private Disposable subscription;

    public OrderQueueConsumer(SqsAsyncClient sqsClient, IProcessOrder processOrder,
                               ObjectMapper objectMapper,
                               @Value("${aws.sqs.queue-url}") String queueUrl) {
        this.sqsClient = sqsClient;
        this.processOrder = processOrder;
        this.objectMapper = objectMapper;
        this.queueUrl = queueUrl;
    }

    @PostConstruct
    public void startPolling() {
        subscription = Flux.interval(Duration.ofSeconds(5))
                .flatMap(tick -> poll())
                .subscribe();
    }

    @PreDestroy
    public void stopPolling() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }

    private Flux<Void> poll() {
        ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(20)
                .build();

        return Mono.fromFuture(() -> sqsClient.receiveMessage(receiveRequest))
                .flatMapMany(resp -> Flux.fromIterable(resp.messages()))
                .flatMap(this::processMessage)
                .onErrorResume(ex -> {
                    // Keep the polling stream alive even if a receive fails
                    // (e.g. queue not yet provisioned, transient network error).
                    log.warn("SQS poll failed, will retry next tick: {}", ex.getMessage());
                    return Flux.empty();
                });
    }

    private Mono<Void> processMessage(Message message) {
        return Mono.fromCallable(() -> objectMapper.readValue(message.body(), OrderMessage.class))
                .flatMap(msg -> processOrder.execute(UUID.fromString(msg.orderId())))
                .then(deleteMessage(message))
                .doOnSuccess(v -> log.info("Processed and deleted message {}", message.messageId()))
                .onErrorResume(ex -> {
                    log.error("Failed to process message {}: {} — leaving for retry/DLQ",
                            message.messageId(), ex.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> deleteMessage(Message message) {
        return Mono.fromFuture(() -> sqsClient.deleteMessage(
                        DeleteMessageRequest.builder()
                                .queueUrl(queueUrl)
                                .receiptHandle(message.receiptHandle())
                                .build()))
                .then();
    }
}
