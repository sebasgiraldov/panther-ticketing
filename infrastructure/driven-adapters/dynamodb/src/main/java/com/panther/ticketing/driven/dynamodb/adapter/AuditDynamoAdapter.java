package com.panther.ticketing.driven.dynamodb.adapter;

import com.panther.ticketing.model.audit.AuditLog;
import com.panther.ticketing.ports.out.AuditGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.panther.ticketing.driven.dynamodb.converter.AttributeValueConverter.*;

@Component
public class AuditDynamoAdapter implements AuditGateway {

    private static final Logger log = LoggerFactory.getLogger(AuditDynamoAdapter.class);

    private final DynamoDbAsyncClient client;
    private final String table;

    public AuditDynamoAdapter(DynamoDbAsyncClient client,
                               @Value("${aws.dynamodb.tables.audit:panther-audit}") String table) {
        this.client = client;
        this.table = table;
    }

    @Override
    public Mono<Void> record(AuditLog auditLog) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("id", s(auditLog.id() != null ? auditLog.id().toString() : UUID.randomUUID().toString()));
        item.put("aggregateType", s(auditLog.aggregateType()));
        item.put("aggregateId", s(auditLog.aggregateId().toString()));
        item.put("action", s(auditLog.action()));
        item.put("detail", s(auditLog.detail()));
        item.put("timestamp", s(formatDateTime(auditLog.timestamp())));

        return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                        .tableName(table).item(item).build()))
                .doOnError(ex -> log.warn("Failed to record audit log: {}", ex.getMessage()))
                .onErrorResume(ex -> Mono.empty())
                .then();
    }
}
