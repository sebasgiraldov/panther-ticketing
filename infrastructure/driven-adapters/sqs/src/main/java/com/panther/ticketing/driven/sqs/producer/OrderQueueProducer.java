package com.panther.ticketing.driven.sqs.producer;

import tools.jackson.databind.ObjectMapper;
import com.panther.ticketing.model.order.Order;
import com.panther.ticketing.ports.out.OrderQueueGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Component
public class OrderQueueProducer implements OrderQueueGateway {

    private static final Logger log = LoggerFactory.getLogger(OrderQueueProducer.class);

    private final SqsAsyncClient sqsClient;
    private final ObjectMapper objectMapper;
    private final String queueUrl;

    public OrderQueueProducer(SqsAsyncClient sqsClient, ObjectMapper objectMapper,
                               @Value("${aws.sqs.queue-url:http://localhost:9324/000000000000/panther-orders}") String queueUrl) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.queueUrl = queueUrl;
    }

    @Override
    public Mono<Void> enqueue(Order order) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(new OrderMessage(order.id().toString())))
                .flatMap(body -> Mono.fromFuture(() -> sqsClient.sendMessage(
                        SendMessageRequest.builder()
                                .queueUrl(queueUrl)
                                .messageBody(body)
                                .build())))
                .doOnSuccess(resp -> log.info("Enqueued order {} messageId={}", order.id(), resp.messageId()))
                .doOnError(ex -> log.error("Failed to enqueue order {}: {}", order.id(), ex.getMessage()))
                .then();
    }

    public record OrderMessage(String orderId) {}
}
