package com.panther.ticketing.driven.dynamodb.adapter;

import com.panther.ticketing.model.enums.TicketStatus;
import com.panther.ticketing.model.exception.ConflictException;
import com.panther.ticketing.model.exception.NotFoundException;
import com.panther.ticketing.model.ticket.Ticket;
import com.panther.ticketing.ports.out.TicketRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.panther.ticketing.driven.dynamodb.converter.AttributeValueConverter.*;

@Component
public class TicketDynamoAdapter implements TicketRepository {

    private final DynamoDbAsyncClient client;
    private final String table;

    public TicketDynamoAdapter(DynamoDbAsyncClient client,
                                @Value("${aws.dynamodb.tables.tickets}") String table) {
        this.client = client;
        this.table = table;
    }

    @Override
    public Mono<Ticket> save(Ticket ticket) {
        return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                        .tableName(table).item(toItem(ticket)).build()))
                .thenReturn(ticket);
    }

    @Override
    public Flux<Ticket> saveAll(List<Ticket> tickets) {
        return Flux.fromIterable(tickets).flatMap(this::save);
    }

    @Override
    public Mono<Ticket> findById(UUID id) {
        return Mono.fromFuture(() -> client.getItem(GetItemRequest.builder()
                        .tableName(table).key(Map.of("id", s(id.toString()))).build()))
                .flatMap(resp -> {
                    if (!resp.hasItem() || resp.item().isEmpty()) {
                        return Mono.error(new NotFoundException("Ticket not found: " + id));
                    }
                    return Mono.just(mapToTicket(resp.item()));
                });
    }

    @Override
    public Flux<Ticket> findByEventIdAndTicketTypeIdAndStatus(UUID eventId, UUID ticketTypeId,
                                                               TicketStatus status, int limit) {
        // status is part of the GSI range key (ticketTypeStatus = "<ticketTypeId>#<status>"),
        // so a key-only Query returns rows that already match the requested status. The
        // Limit is now a true cap: every returned row qualifies, so DynamoDB's Limit-before-
        // filter pitfall cannot cause a false shortage. The conditional write on each ticket
        // remains the authoritative guard; this is just the candidate selection.
        QueryRequest req = QueryRequest.builder()
                .tableName(table)
                .indexName("eventId-ticketTypeStatus-index")
                .keyConditionExpression("eventId = :eid AND ticketTypeStatus = :tts")
                .expressionAttributeValues(Map.of(
                        ":eid", s(eventId.toString()),
                        ":tts", s(ticketTypeId.toString() + "#" + status.name())))
                .limit(limit)
                .build();
        return Mono.fromFuture(() -> client.query(req))
                .flatMapMany(resp -> Flux.fromIterable(resp.items())
                        .map(this::mapToTicket)
                        .take(limit));
    }

    @Override
    public Flux<Ticket> findByReservationId(UUID reservationId) {
        QueryRequest req = QueryRequest.builder()
                .tableName(table)
                .indexName("reservationId-index")
                .keyConditionExpression("reservationId = :rid")
                .expressionAttributeValues(Map.of(":rid", s(reservationId.toString())))
                .build();
        return Mono.fromFuture(() -> client.query(req))
                .flatMapMany(resp -> Flux.fromIterable(resp.items()).map(this::mapToTicket));
    }

    @Override
    public Mono<Ticket> updateStatusConditional(Ticket ticket, TicketStatus expectedStatus) {
        Ticket updated = new Ticket(ticket.id(), ticket.eventId(), ticket.ticketTypeId(),
                ticket.status(), ticket.price(), ticket.seatNumber(), ticket.ownerRef(),
                ticket.reservationId(), ticket.version() + 1, ticket.createdAt(), LocalDateTime.now());

        return Mono.fromFuture(() -> client.updateItem(UpdateItemRequest.builder()
                        .tableName(table)
                        .key(Map.of("id", s(ticket.id().toString())))
                        .updateExpression("SET #s = :newStatus, ticketTypeStatus = :tts, ownerRef = :owner, reservationId = :rid, #v = :newVersion, updatedAt = :updatedAt")
                        .conditionExpression("#s = :expectedStatus AND #v = :expectedVersion")
                        .expressionAttributeNames(Map.of("#s", "status", "#v", "version"))
                        .expressionAttributeValues(Map.of(
                                ":newStatus", s(ticket.status().name()),
                                ":tts", s(ticket.ticketTypeId().toString() + "#" + ticket.status().name()),
                                ":expectedStatus", s(expectedStatus.name()),
                                ":expectedVersion", n(ticket.version()),
                                ":newVersion", n(updated.version()),
                                ":owner", ticket.ownerRef() != null ? s(ticket.ownerRef()) : AttributeValue.builder().nul(true).build(),
                                ":rid", ticket.reservationId() != null ? s(ticket.reservationId().toString()) : AttributeValue.builder().nul(true).build(),
                                ":updatedAt", s(formatDateTime(updated.updatedAt()))))
                        .build()))
                .thenReturn(updated)
                .onErrorMap(ConditionalCheckFailedException.class,
                        ex -> new ConflictException("Ticket conditional check failed: " + ticket.id()));
    }

    private Map<String, AttributeValue> toItem(Ticket t) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("id", s(t.id().toString()));
        item.put("eventId", s(t.eventId().toString()));
        item.put("ticketTypeId", s(t.ticketTypeId().toString()));
        item.put("status", s(t.status().name()));
        item.put("ticketTypeStatus", s(t.ticketTypeId().toString() + "#" + t.status().name()));
        item.put("price", n(t.price()));
        item.put("version", n(t.version()));
        if (t.seatNumber() != null) item.put("seatNumber", s(t.seatNumber()));
        if (t.ownerRef() != null) item.put("ownerRef", s(t.ownerRef()));
        if (t.reservationId() != null) item.put("reservationId", s(t.reservationId().toString()));
        if (t.createdAt() != null) item.put("createdAt", s(formatDateTime(t.createdAt())));
        if (t.updatedAt() != null) item.put("updatedAt", s(formatDateTime(t.updatedAt())));
        return item;
    }

    private Ticket mapToTicket(Map<String, AttributeValue> item) {
        String ridStr = getString(item, "reservationId");
        return new Ticket(
                getUUID(item, "id"), getUUID(item, "eventId"), getUUID(item, "ticketTypeId"),
                TicketStatus.valueOf(getString(item, "status")),
                getBigDecimal(item, "price"), getString(item, "seatNumber"),
                getString(item, "ownerRef"),
                ridStr != null ? UUID.fromString(ridStr) : null,
                getLong(item, "version"),
                getDateTime(item, "createdAt"), getDateTime(item, "updatedAt"));
    }
}
