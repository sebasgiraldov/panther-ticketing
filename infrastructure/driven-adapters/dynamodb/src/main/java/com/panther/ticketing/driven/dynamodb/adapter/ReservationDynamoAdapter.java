package com.panther.ticketing.driven.dynamodb.adapter;

import com.panther.ticketing.model.enums.ReservationStatus;
import com.panther.ticketing.model.exception.ConflictException;
import com.panther.ticketing.model.exception.NotFoundException;
import com.panther.ticketing.model.reservation.Reservation;
import com.panther.ticketing.ports.out.ReservationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.panther.ticketing.driven.dynamodb.converter.AttributeValueConverter.*;

@Component
public class ReservationDynamoAdapter implements ReservationRepository {

    private final DynamoDbAsyncClient client;
    private final String table;

    public ReservationDynamoAdapter(DynamoDbAsyncClient client,
                                     @Value("${aws.dynamodb.tables.reservations:panther-reservations}") String table) {
        this.client = client;
        this.table = table;
    }

    @Override
    public Mono<Reservation> save(Reservation reservation) {
        return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                        .tableName(table).item(toItem(reservation)).build()))
                .thenReturn(reservation);
    }

    @Override
    public Mono<Reservation> findById(UUID id) {
        return Mono.fromFuture(() -> client.getItem(GetItemRequest.builder()
                        .tableName(table).key(Map.of("id", s(id.toString()))).build()))
                .flatMap(resp -> {
                    if (!resp.hasItem() || resp.item().isEmpty()) {
                        return Mono.error(new NotFoundException("Reservation not found: " + id));
                    }
                    return Mono.just(mapToReservation(resp.item()));
                });
    }

    @Override
    public Flux<Reservation> findByStatusAndExpiresAtBefore(ReservationStatus status, LocalDateTime threshold) {
        // Scan with filter — acceptable for scheduler (low frequency, bounded result set)
        ScanRequest req = ScanRequest.builder()
                .tableName(table)
                .filterExpression("#s = :status AND expiresAt < :threshold")
                .expressionAttributeNames(Map.of("#s", "status"))
                .expressionAttributeValues(Map.of(
                        ":status", s(status.name()),
                        ":threshold", s(formatDateTime(threshold))))
                .build();
        return Mono.fromFuture(() -> client.scan(req))
                .flatMapMany(resp -> Flux.fromIterable(resp.items()).map(this::mapToReservation));
    }

    @Override
    public Mono<Reservation> updateStatusConditional(Reservation reservation, ReservationStatus expectedStatus) {
        Reservation updated = new Reservation(reservation.id(), reservation.eventId(),
                reservation.ticketTypeId(), reservation.ticketIds(), reservation.customerRef(),
                reservation.expiresAt(), reservation.status(), reservation.version() + 1, reservation.createdAt());

        return Mono.fromFuture(() -> client.updateItem(UpdateItemRequest.builder()
                        .tableName(table)
                        .key(Map.of("id", s(reservation.id().toString())))
                        .updateExpression("SET #s = :newStatus, #v = :newVersion")
                        .conditionExpression("#s = :expectedStatus AND #v = :expectedVersion")
                        .expressionAttributeNames(Map.of("#s", "status", "#v", "version"))
                        .expressionAttributeValues(Map.of(
                                ":newStatus", s(reservation.status().name()),
                                ":expectedStatus", s(expectedStatus.name()),
                                ":expectedVersion", n(reservation.version()),
                                ":newVersion", n(updated.version())))
                        .build()))
                .thenReturn(updated)
                .onErrorMap(ConditionalCheckFailedException.class,
                        ex -> new ConflictException("Reservation conditional check failed: " + reservation.id()));
    }

    private Map<String, AttributeValue> toItem(Reservation r) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("id", s(r.id().toString()));
        item.put("eventId", s(r.eventId().toString()));
        item.put("ticketTypeId", s(r.ticketTypeId().toString()));
        item.put("customerRef", s(r.customerRef()));
        item.put("expiresAt", s(formatDateTime(r.expiresAt())));
        item.put("status", s(r.status().name()));
        item.put("version", n(r.version()));
        if (r.createdAt() != null) item.put("createdAt", s(formatDateTime(r.createdAt())));
        List<AttributeValue> ticketIdAttrs = r.ticketIds().stream()
                .map(id -> AttributeValue.builder().s(id.toString()).build())
                .collect(Collectors.toList());
        item.put("ticketIds", AttributeValue.builder().l(ticketIdAttrs).build());
        return item;
    }

    private Reservation mapToReservation(Map<String, AttributeValue> item) {
        List<UUID> ticketIds = item.containsKey("ticketIds")
                ? item.get("ticketIds").l().stream().map(av -> UUID.fromString(av.s())).toList()
                : List.of();
        return new Reservation(
                getUUID(item, "id"), getUUID(item, "eventId"), getUUID(item, "ticketTypeId"),
                ticketIds, getString(item, "customerRef"),
                getDateTime(item, "expiresAt"),
                ReservationStatus.valueOf(getString(item, "status")),
                getLong(item, "version"), getDateTime(item, "createdAt"));
    }
}
