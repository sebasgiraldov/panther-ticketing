package com.panther.ticketing.driven.dynamodb.adapter;

import com.panther.ticketing.model.audit.AuditLog;
import com.panther.ticketing.model.enums.OrderStatus;
import com.panther.ticketing.model.enums.ReservationStatus;
import com.panther.ticketing.model.exception.ConflictException;
import com.panther.ticketing.model.exception.NotFoundException;
import com.panther.ticketing.model.order.Order;
import com.panther.ticketing.model.reservation.Reservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.panther.ticketing.driven.dynamodb.converter.AttributeValueConverter.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamoAdaptersTest {

    @Mock DynamoDbAsyncClient client;

    OrderDynamoAdapter orderAdapter;
    ReservationDynamoAdapter reservationAdapter;
    AuditDynamoAdapter auditAdapter;

    UUID id = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID ttId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();
    LocalDateTime now = LocalDateTime.of(2026, 1, 1, 10, 0);

    @BeforeEach
    void setUp() {
        orderAdapter = new OrderDynamoAdapter(client, "orders");
        reservationAdapter = new ReservationDynamoAdapter(client, "reservations");
        auditAdapter = new AuditDynamoAdapter(client, "audit");
    }

    // ===== OrderDynamoAdapter =====

    @Test
    void order_save_persistsAndReturns() {
        Order order = order(OrderStatus.PENDING);
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(PutItemResponse.builder().build()));

        StepVerifier.create(orderAdapter.save(order))
                .expectNext(order)
                .verifyComplete();
    }

    @Test
    void order_findById_found_returnsOrder() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        GetItemResponse.builder().item(orderItem(OrderStatus.PENDING)).build()));

        StepVerifier.create(orderAdapter.findById(id))
                .expectNextMatches(o -> o.status() == OrderStatus.PENDING)
                .verifyComplete();
    }

    @Test
    void order_findById_notFound_throwsNotFoundException() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder().build()));

        StepVerifier.create(orderAdapter.findById(id))
                .expectError(NotFoundException.class)
                .verify();
    }

    @Test
    void order_findByIdempotencyKey_found_returnsOrder() {
        when(client.query(any(QueryRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        QueryResponse.builder().items(List.of(orderItem(OrderStatus.CONFIRMED))).build()));

        StepVerifier.create(orderAdapter.findByIdempotencyKey("idem-key"))
                .expectNextMatches(o -> o.status() == OrderStatus.CONFIRMED)
                .verifyComplete();
    }

    @Test
    void order_findByIdempotencyKey_notFound_returnsEmpty() {
        when(client.query(any(QueryRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        QueryResponse.builder().items(List.of()).build()));

        StepVerifier.create(orderAdapter.findByIdempotencyKey("idem-key"))
                .verifyComplete();
    }

    @Test
    void order_updateStatusConditional_success_returnsUpdated() {
        Order order = order(OrderStatus.PROCESSING);
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(UpdateItemResponse.builder().build()));

        StepVerifier.create(orderAdapter.updateStatusConditional(order))
                .expectNextMatches(o -> o.version() == 1L)
                .verifyComplete();
    }

    @Test
    void order_updateStatusConditional_conditionalFail_throwsConflictException() {
        Order order = order(OrderStatus.PROCESSING);
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        ConditionalCheckFailedException.builder().message("failed").build()));

        StepVerifier.create(orderAdapter.updateStatusConditional(order))
                .expectError(ConflictException.class)
                .verify();
    }

    // ===== ReservationDynamoAdapter =====

    @Test
    void reservation_save_persistsAndReturns() {
        Reservation reservation = reservation(ReservationStatus.ACTIVE);
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(PutItemResponse.builder().build()));

        StepVerifier.create(reservationAdapter.save(reservation))
                .expectNext(reservation)
                .verifyComplete();
    }

    @Test
    void reservation_findById_found_returnsReservation() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        GetItemResponse.builder().item(reservationItem(ReservationStatus.ACTIVE)).build()));

        StepVerifier.create(reservationAdapter.findById(reservationId))
                .expectNextMatches(r -> r.status() == ReservationStatus.ACTIVE)
                .verifyComplete();
    }

    @Test
    void reservation_findById_notFound_throwsNotFoundException() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder().build()));

        StepVerifier.create(reservationAdapter.findById(reservationId))
                .expectError(NotFoundException.class)
                .verify();
    }

    @Test
    void reservation_findByStatusAndExpiresAtBefore_returnsFlux() {
        when(client.scan(any(ScanRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ScanResponse.builder().items(List.of(reservationItem(ReservationStatus.ACTIVE))).build()));

        StepVerifier.create(reservationAdapter.findByStatusAndExpiresAtBefore(ReservationStatus.ACTIVE, now))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void reservation_updateStatusConditional_success_returnsUpdated() {
        Reservation reservation = reservation(ReservationStatus.EXPIRED);
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(UpdateItemResponse.builder().build()));

        StepVerifier.create(reservationAdapter.updateStatusConditional(reservation, ReservationStatus.ACTIVE))
                .expectNextMatches(r -> r.version() == 1L)
                .verifyComplete();
    }

    @Test
    void reservation_updateStatusConditional_conditionalFail_throwsConflictException() {
        Reservation reservation = reservation(ReservationStatus.EXPIRED);
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        ConditionalCheckFailedException.builder().message("failed").build()));

        StepVerifier.create(reservationAdapter.updateStatusConditional(reservation, ReservationStatus.ACTIVE))
                .expectError(ConflictException.class)
                .verify();
    }

    // ===== AuditDynamoAdapter =====

    @Test
    void audit_record_success_completesEmpty() {
        AuditLog log = new AuditLog(UUID.randomUUID(), "Order", id, "CONFIRMED", "detail", now);
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(PutItemResponse.builder().build()));

        StepVerifier.create(auditAdapter.record(log))
                .verifyComplete();
    }

    @Test
    void audit_record_clientError_swallowsAndCompletes() {
        AuditLog log = new AuditLog(UUID.randomUUID(), "Order", id, "CONFIRMED", "detail", now);
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("DynamoDB down")));

        StepVerifier.create(auditAdapter.record(log))
                .verifyComplete();
    }

    @Test
    void audit_record_nullId_generatesId() {
        AuditLog log = new AuditLog(null, "Order", id, "CONFIRMED", "detail", now);
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(PutItemResponse.builder().build()));

        StepVerifier.create(auditAdapter.record(log))
                .verifyComplete();
    }

    // --- helpers ---

    private Order order(OrderStatus status) {
        return new Order(id, reservationId, ttId, eventId, "customer", 2,
                BigDecimal.valueOf(100), status, "idem-key", 0L, now, now);
    }

    private Map<String, AttributeValue> orderItem(OrderStatus status) {
        return Map.of(
                "id", s(id.toString()), "reservationId", s(reservationId.toString()),
                "ticketTypeId", s(ttId.toString()), "eventId", s(eventId.toString()),
                "customerRef", s("customer"), "quantity", n(2),
                "totalAmount", n(100), "status", s(status.name()),
                "idempotencyKey", s("idem-key"), "version", n(0L));
    }

    private Reservation reservation(ReservationStatus status) {
        return new Reservation(reservationId, eventId, ttId, List.of(UUID.randomUUID()),
                "customer", now.plusMinutes(10), status, 0L, now);
    }

    private Map<String, AttributeValue> reservationItem(ReservationStatus status) {
        AttributeValue ticketIdAttr = AttributeValue.builder()
                .l(AttributeValue.builder().s(UUID.randomUUID().toString()).build())
                .build();
        return Map.of(
                "id", s(reservationId.toString()), "eventId", s(eventId.toString()),
                "ticketTypeId", s(ttId.toString()), "customerRef", s("customer"),
                "expiresAt", s(formatDateTime(now.plusMinutes(10))),
                "status", s(status.name()), "version", n(0L),
                "createdAt", s(formatDateTime(now)), "ticketIds", ticketIdAttr);
    }
}
