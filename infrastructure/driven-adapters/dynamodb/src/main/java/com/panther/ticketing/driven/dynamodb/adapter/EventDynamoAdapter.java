package com.panther.ticketing.driven.dynamodb.adapter;

import com.panther.ticketing.driven.dynamodb.converter.AttributeValueConverter;
import com.panther.ticketing.model.event.Event;
import com.panther.ticketing.model.event.InventoryCounter;
import com.panther.ticketing.model.event.TicketType;
import com.panther.ticketing.model.exception.ConflictException;
import com.panther.ticketing.model.exception.NotFoundException;
import com.panther.ticketing.ports.out.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.panther.ticketing.driven.dynamodb.converter.AttributeValueConverter.*;

@Component
public class EventDynamoAdapter implements EventRepository {

    private static final Logger log = LoggerFactory.getLogger(EventDynamoAdapter.class);

    private final DynamoDbAsyncClient client;
    private final String eventsTable;
    private final String ticketTypesTable;
    private final String inventoryTable;

    public EventDynamoAdapter(DynamoDbAsyncClient client,
                               @Value("${aws.dynamodb.tables.events}") String eventsTable,
                               @Value("${aws.dynamodb.tables.ticket-types}") String ticketTypesTable,
                               @Value("${aws.dynamodb.tables.inventory}") String inventoryTable) {
        this.client = client;
        this.eventsTable = eventsTable;
        this.ticketTypesTable = ticketTypesTable;
        this.inventoryTable = inventoryTable;
    }

    @Override
    public Mono<Event> save(Event event) {
        Event toSave = event.id() != null ? event
                : new Event(UUID.randomUUID(), event.name(), event.description(),
                event.date(), event.venue(), event.totalCapacity(), LocalDateTime.now());

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("id", s(toSave.id().toString()));
        item.put("name", s(toSave.name()));
        item.put("description", s(toSave.description()));
        item.put("date", s(formatDateTime(toSave.date())));
        item.put("venue", s(toSave.venue()));
        item.put("totalCapacity", n(toSave.totalCapacity()));
        item.put("createdAt", s(formatDateTime(toSave.createdAt())));

        return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                        .tableName(eventsTable).item(item).build()))
                .thenReturn(toSave)
                .onErrorMap(ex -> new RuntimeException("Failed to save event: " + ex.getMessage(), ex));
    }

    @Override
    public Mono<Event> findById(UUID id) {
        return Mono.fromFuture(() -> client.getItem(GetItemRequest.builder()
                        .tableName(eventsTable)
                        .key(Map.of("id", s(id.toString())))
                        .build()))
                .flatMap(resp -> {
                    if (!resp.hasItem() || resp.item().isEmpty()) {
                        return Mono.error(new NotFoundException("Event not found: " + id));
                    }
                    return Mono.just(mapToEvent(resp.item()));
                });
    }

    @Override
    public Mono<TicketType> saveTicketType(TicketType tt) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("id", s(tt.id().toString()));
        item.put("eventId", s(tt.eventId().toString()));
        item.put("name", s(tt.name()));
        item.put("price", n(tt.price()));
        item.put("totalQuantity", n(tt.totalQuantity()));

        return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                        .tableName(ticketTypesTable).item(item).build()))
                .thenReturn(tt);
    }

    @Override
    public Flux<TicketType> findTicketTypesByEventId(UUID eventId) {
        QueryRequest req = QueryRequest.builder()
                .tableName(ticketTypesTable)
                .indexName("eventId-index")
                .keyConditionExpression("eventId = :eid")
                .expressionAttributeValues(Map.of(":eid", s(eventId.toString())))
                .build();
        return Mono.fromFuture(() -> client.query(req))
                .flatMapMany(resp -> Flux.fromIterable(resp.items()).map(this::mapToTicketType));
    }

    @Override
    public Mono<TicketType> findTicketTypeById(UUID id) {
        return Mono.fromFuture(() -> client.getItem(GetItemRequest.builder()
                        .tableName(ticketTypesTable)
                        .key(Map.of("id", s(id.toString())))
                        .build()))
                .flatMap(resp -> {
                    if (!resp.hasItem() || resp.item().isEmpty()) {
                        return Mono.error(new NotFoundException("TicketType not found: " + id));
                    }
                    return Mono.just(mapToTicketType(resp.item()));
                });
    }

    @Override
    public Mono<InventoryCounter> findInventoryCounter(UUID eventId, UUID ticketTypeId) {
        String pk = eventId + "#" + ticketTypeId;
        return Mono.fromFuture(() -> client.getItem(GetItemRequest.builder()
                        .tableName(inventoryTable)
                        .key(Map.of("pk", s(pk)))
                        .build()))
                .flatMap(resp -> {
                    if (!resp.hasItem() || resp.item().isEmpty()) {
                        return Mono.error(new NotFoundException("Inventory not found for " + pk));
                    }
                    return Mono.just(mapToCounter(resp.item()));
                });
    }

    @Override
    public Mono<InventoryCounter> saveInventoryCounter(InventoryCounter counter) {
        String pk = counter.eventId() + "#" + counter.ticketTypeId();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", s(pk));
        item.put("eventId", s(counter.eventId().toString()));
        item.put("ticketTypeId", s(counter.ticketTypeId().toString()));
        item.put("availableCount", n(counter.availableCount()));
        item.put("reservedCount", n(counter.reservedCount()));
        item.put("soldCount", n(counter.soldCount()));
        item.put("version", n(counter.version()));

        return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                        .tableName(inventoryTable).item(item).build()))
                .thenReturn(counter);
    }

    @Override
    public Mono<InventoryCounter> decrementAvailableIncrementReserved(UUID eventId, UUID ticketTypeId,
                                                                        int quantity, long expectedVersion) {
        String pk = eventId + "#" + ticketTypeId;
        return Mono.fromFuture(() -> client.updateItem(UpdateItemRequest.builder()
                        .tableName(inventoryTable)
                        .key(Map.of("pk", s(pk)))
                        .updateExpression("SET availableCount = availableCount - :qty, reservedCount = reservedCount + :qty, #v = #v + :one")
                        .conditionExpression("availableCount >= :qty AND #v = :expectedVersion")
                        .expressionAttributeNames(Map.of("#v", "version"))
                        .expressionAttributeValues(Map.of(
                                ":qty", n(quantity),
                                ":one", n(1),
                                ":expectedVersion", n(expectedVersion)))
                        .returnValues(ReturnValue.ALL_NEW)
                        .build()))
                .map(resp -> mapToCounter(resp.attributes()))
                .onErrorMap(ConditionalCheckFailedException.class,
                        ex -> new ConflictException("Inventory conditional check failed: concurrent modification"));
    }

    @Override
    public Mono<InventoryCounter> decrementReservedIncrementSold(UUID eventId, UUID ticketTypeId,
                                                                   int quantity, long expectedVersion) {
        String pk = eventId + "#" + ticketTypeId;
        return Mono.fromFuture(() -> client.updateItem(UpdateItemRequest.builder()
                        .tableName(inventoryTable)
                        .key(Map.of("pk", s(pk)))
                        .updateExpression("SET reservedCount = reservedCount - :qty, soldCount = soldCount + :qty, #v = #v + :one")
                        .conditionExpression("reservedCount >= :qty AND #v = :expectedVersion")
                        .expressionAttributeNames(Map.of("#v", "version"))
                        .expressionAttributeValues(Map.of(
                                ":qty", n(quantity),
                                ":one", n(1),
                                ":expectedVersion", n(expectedVersion)))
                        .returnValues(ReturnValue.ALL_NEW)
                        .build()))
                .map(resp -> mapToCounter(resp.attributes()))
                .onErrorMap(ConditionalCheckFailedException.class,
                        ex -> new ConflictException("Inventory conditional check failed"));
    }

    @Override
    public Mono<InventoryCounter> decrementReservedIncrementAvailable(UUID eventId, UUID ticketTypeId,
                                                                        int quantity, long expectedVersion) {
        String pk = eventId + "#" + ticketTypeId;
        return Mono.fromFuture(() -> client.updateItem(UpdateItemRequest.builder()
                        .tableName(inventoryTable)
                        .key(Map.of("pk", s(pk)))
                        .updateExpression("SET reservedCount = reservedCount - :qty, availableCount = availableCount + :qty, #v = #v + :one")
                        .conditionExpression("reservedCount >= :qty AND #v = :expectedVersion")
                        .expressionAttributeNames(Map.of("#v", "version"))
                        .expressionAttributeValues(Map.of(
                                ":qty", n(quantity),
                                ":one", n(1),
                                ":expectedVersion", n(expectedVersion)))
                        .returnValues(ReturnValue.ALL_NEW)
                        .build()))
                .map(resp -> mapToCounter(resp.attributes()))
                .onErrorMap(ConditionalCheckFailedException.class,
                        ex -> new ConflictException("Inventory conditional check failed"));
    }

    @Override
    public Mono<InventoryCounter> decrementReservedForComplimentary(UUID eventId, UUID ticketTypeId,
                                                                     int quantity, long expectedVersion) {
        String pk = eventId + "#" + ticketTypeId;
        return Mono.fromFuture(() -> client.updateItem(UpdateItemRequest.builder()
                        .tableName(inventoryTable)
                        .key(Map.of("pk", s(pk)))
                        .updateExpression("SET reservedCount = reservedCount - :qty, #v = #v + :one")
                        .conditionExpression("reservedCount >= :qty AND #v = :expectedVersion")
                        .expressionAttributeNames(Map.of("#v", "version"))
                        .expressionAttributeValues(Map.of(
                                ":qty", n(quantity),
                                ":one", n(1),
                                ":expectedVersion", n(expectedVersion)))
                        .returnValues(ReturnValue.ALL_NEW)
                        .build()))
                .map(resp -> mapToCounter(resp.attributes()))
                .onErrorMap(ConditionalCheckFailedException.class,
                        ex -> new ConflictException("Inventory conditional check failed (complimentary)"));
    }

    private Event mapToEvent(Map<String, AttributeValue> item) {
        return new Event(
                getUUID(item, "id"), getString(item, "name"), getString(item, "description"),
                getDateTime(item, "date"), getString(item, "venue"),
                getInt(item, "totalCapacity"), getDateTime(item, "createdAt"));
    }

    private TicketType mapToTicketType(Map<String, AttributeValue> item) {
        return new TicketType(
                getUUID(item, "id"), getUUID(item, "eventId"),
                getString(item, "name"), getBigDecimal(item, "price"),
                getInt(item, "totalQuantity"));
    }

    private InventoryCounter mapToCounter(Map<String, AttributeValue> item) {
        return new InventoryCounter(
                getUUID(item, "eventId"), getUUID(item, "ticketTypeId"),
                getInt(item, "availableCount"), getInt(item, "reservedCount"),
                getInt(item, "soldCount"), getLong(item, "version"));
    }
}
