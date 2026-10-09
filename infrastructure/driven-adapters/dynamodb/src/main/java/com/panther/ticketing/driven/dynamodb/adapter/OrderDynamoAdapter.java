package com.panther.ticketing.driven.dynamodb.adapter;

import com.panther.ticketing.model.enums.OrderStatus;
import com.panther.ticketing.model.exception.ConflictException;
import com.panther.ticketing.model.exception.NotFoundException;
import com.panther.ticketing.model.order.Order;
import com.panther.ticketing.ports.out.OrderRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.panther.ticketing.driven.dynamodb.converter.AttributeValueConverter.*;

@Component
public class OrderDynamoAdapter implements OrderRepository {

    private final DynamoDbAsyncClient client;
    private final String table;

    public OrderDynamoAdapter(DynamoDbAsyncClient client,
                               @Value("${aws.dynamodb.tables.orders:panther-orders}") String table) {
        this.client = client;
        this.table = table;
    }

    @Override
    public Mono<Order> save(Order order) {
        return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                        .tableName(table).item(toItem(order)).build()))
                .thenReturn(order);
    }

    @Override
    public Mono<Order> findById(UUID id) {
        return Mono.fromFuture(() -> client.getItem(GetItemRequest.builder()
                        .tableName(table).key(Map.of("id", s(id.toString()))).build()))
                .flatMap(resp -> {
                    if (!resp.hasItem() || resp.item().isEmpty()) {
                        return Mono.error(new NotFoundException("Order not found: " + id));
                    }
                    return Mono.just(mapToOrder(resp.item()));
                });
    }

    @Override
    public Mono<Order> findByIdempotencyKey(String idempotencyKey) {
        QueryRequest req = QueryRequest.builder()
                .tableName(table)
                .indexName("idempotencyKey-index")
                .keyConditionExpression("idempotencyKey = :key")
                .expressionAttributeValues(Map.of(":key", s(idempotencyKey)))
                .limit(1)
                .build();
        return Mono.fromFuture(() -> client.query(req))
                .flatMap(resp -> resp.items().isEmpty()
                        ? Mono.empty()
                        : Mono.just(mapToOrder(resp.items().get(0))));
    }

    @Override
    public Mono<Order> updateStatusConditional(Order order) {
        Order updated = new Order(order.id(), order.reservationId(), order.ticketTypeId(),
                order.eventId(), order.customerRef(), order.quantity(), order.totalAmount(),
                order.status(), order.idempotencyKey(), order.version() + 1,
                order.createdAt(), order.updatedAt());

        return Mono.fromFuture(() -> client.updateItem(UpdateItemRequest.builder()
                        .tableName(table)
                        .key(Map.of("id", s(order.id().toString())))
                        .updateExpression("SET #s = :newStatus, #v = :newVersion, updatedAt = :updatedAt")
                        .conditionExpression("#v = :expectedVersion")
                        .expressionAttributeNames(Map.of("#s", "status", "#v", "version"))
                        .expressionAttributeValues(Map.of(
                                ":newStatus", s(order.status().name()),
                                ":expectedVersion", n(order.version()),
                                ":newVersion", n(updated.version()),
                                ":updatedAt", s(formatDateTime(updated.updatedAt()))))
                        .build()))
                .thenReturn(updated)
                .onErrorMap(ConditionalCheckFailedException.class,
                        ex -> new ConflictException("Order conditional check failed: " + order.id()));
    }

    private Map<String, AttributeValue> toItem(Order o) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("id", s(o.id().toString()));
        item.put("reservationId", s(o.reservationId().toString()));
        item.put("ticketTypeId", s(o.ticketTypeId().toString()));
        item.put("eventId", s(o.eventId().toString()));
        item.put("customerRef", s(o.customerRef()));
        item.put("quantity", n(o.quantity()));
        item.put("totalAmount", n(o.totalAmount()));
        item.put("status", s(o.status().name()));
        item.put("idempotencyKey", s(o.idempotencyKey()));
        item.put("version", n(o.version()));
        if (o.createdAt() != null) item.put("createdAt", s(formatDateTime(o.createdAt())));
        if (o.updatedAt() != null) item.put("updatedAt", s(formatDateTime(o.updatedAt())));
        return item;
    }

    private Order mapToOrder(Map<String, AttributeValue> item) {
        return new Order(
                getUUID(item, "id"), getUUID(item, "reservationId"),
                getUUID(item, "ticketTypeId"), getUUID(item, "eventId"),
                getString(item, "customerRef"), getInt(item, "quantity"),
                getBigDecimal(item, "totalAmount"),
                OrderStatus.valueOf(getString(item, "status")),
                getString(item, "idempotencyKey"), getLong(item, "version"),
                getDateTime(item, "createdAt"), getDateTime(item, "updatedAt"));
    }
}
