package com.panther.ticketing.driven.dynamodb.adapter;

import com.panther.ticketing.model.event.Event;
import com.panther.ticketing.model.event.InventoryCounter;
import com.panther.ticketing.model.event.TicketType;
import com.panther.ticketing.model.exception.ConflictException;
import com.panther.ticketing.model.exception.NotFoundException;
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
class EventDynamoAdapterTest {

    @Mock DynamoDbAsyncClient client;

    EventDynamoAdapter adapter;

    UUID eventId = UUID.randomUUID();
    UUID ttId = UUID.randomUUID();
    LocalDateTime now = LocalDateTime.of(2026, 1, 1, 10, 0);

    @BeforeEach
    void setUp() {
        adapter = new EventDynamoAdapter(client, "events", "ticket-types", "inventory");
    }

    // --- save ---

    @Test
    void save_persistsAndReturnsEvent() {
        Event event = new Event(eventId, "Concert", "Desc", now, "Venue", 100, now);
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(PutItemResponse.builder().build()));

        StepVerifier.create(adapter.save(event))
                .expectNextMatches(e -> e.id().equals(eventId))
                .verifyComplete();
    }

    @Test
    void save_nullId_generatesId() {
        Event event = new Event(null, "Concert", "Desc", now, "Venue", 100, now);
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(PutItemResponse.builder().build()));

        StepVerifier.create(adapter.save(event))
                .expectNextMatches(e -> e.id() != null)
                .verifyComplete();
    }

    @Test
    void save_clientError_wrapsException() {
        Event event = new Event(eventId, "Concert", "Desc", now, "Venue", 100, now);
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("DynamoDB down")));

        StepVerifier.create(adapter.save(event))
                .expectError(RuntimeException.class)
                .verify();
    }

    // --- findById ---

    @Test
    void findById_found_returnsEvent() {
        Map<String, AttributeValue> item = Map.of(
                "id", s(eventId.toString()), "name", s("Concert"), "description", s("Desc"),
                "date", s(formatDateTime(now)), "venue", s("Venue"),
                "totalCapacity", n(100), "createdAt", s(formatDateTime(now)));
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        GetItemResponse.builder().item(item).build()));

        StepVerifier.create(adapter.findById(eventId))
                .expectNextMatches(e -> e.name().equals("Concert"))
                .verifyComplete();
    }

    @Test
    void findById_notFound_throwsNotFoundException() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        GetItemResponse.builder().build()));

        StepVerifier.create(adapter.findById(eventId))
                .expectError(NotFoundException.class)
                .verify();
    }

    // --- saveTicketType ---

    @Test
    void saveTicketType_persistsAndReturns() {
        TicketType tt = new TicketType(ttId, eventId, "General", BigDecimal.valueOf(50), 10);
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(PutItemResponse.builder().build()));

        StepVerifier.create(adapter.saveTicketType(tt))
                .expectNext(tt)
                .verifyComplete();
    }

    // --- findTicketTypesByEventId ---

    @Test
    void findTicketTypesByEventId_returnsFlux() {
        Map<String, AttributeValue> item = Map.of(
                "id", s(ttId.toString()), "eventId", s(eventId.toString()),
                "name", s("General"), "price", n(50), "totalQuantity", n(10));
        when(client.query(any(QueryRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        QueryResponse.builder().items(List.of(item)).build()));

        StepVerifier.create(adapter.findTicketTypesByEventId(eventId))
                .expectNextMatches(tt -> tt.name().equals("General"))
                .verifyComplete();
    }

    // --- findTicketTypeById ---

    @Test
    void findTicketTypeById_found_returns() {
        Map<String, AttributeValue> item = Map.of(
                "id", s(ttId.toString()), "eventId", s(eventId.toString()),
                "name", s("VIP"), "price", n(200), "totalQuantity", n(5));
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        GetItemResponse.builder().item(item).build()));

        StepVerifier.create(adapter.findTicketTypeById(ttId))
                .expectNextMatches(tt -> tt.name().equals("VIP"))
                .verifyComplete();
    }

    @Test
    void findTicketTypeById_notFound_throwsNotFoundException() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder().build()));

        StepVerifier.create(adapter.findTicketTypeById(ttId))
                .expectError(NotFoundException.class)
                .verify();
    }

    // --- findInventoryCounter ---

    @Test
    void findInventoryCounter_found_returns() {
        Map<String, AttributeValue> item = Map.of(
                "eventId", s(eventId.toString()), "ticketTypeId", s(ttId.toString()),
                "availableCount", n(8), "reservedCount", n(2), "soldCount", n(0), "version", n(1));
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        GetItemResponse.builder().item(item).build()));

        StepVerifier.create(adapter.findInventoryCounter(eventId, ttId))
                .expectNextMatches(c -> c.availableCount() == 8)
                .verifyComplete();
    }

    @Test
    void findInventoryCounter_notFound_throwsNotFoundException() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder().build()));

        StepVerifier.create(adapter.findInventoryCounter(eventId, ttId))
                .expectError(NotFoundException.class)
                .verify();
    }

    // --- saveInventoryCounter ---

    @Test
    void saveInventoryCounter_persistsAndReturns() {
        InventoryCounter counter = new InventoryCounter(eventId, ttId, 10, 0, 0, 0L);
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(PutItemResponse.builder().build()));

        StepVerifier.create(adapter.saveInventoryCounter(counter))
                .expectNext(counter)
                .verifyComplete();
    }

    // --- decrementAvailableIncrementReserved ---

    @Test
    void decrementAvailableIncrementReserved_success_returnsUpdatedCounter() {
        Map<String, AttributeValue> attrs = Map.of(
                "eventId", s(eventId.toString()), "ticketTypeId", s(ttId.toString()),
                "availableCount", n(8), "reservedCount", n(2), "soldCount", n(0), "version", n(1));
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        UpdateItemResponse.builder().attributes(attrs).build()));

        StepVerifier.create(adapter.decrementAvailableIncrementReserved(eventId, ttId, 2, 0L))
                .expectNextMatches(c -> c.reservedCount() == 2)
                .verifyComplete();
    }

    @Test
    void decrementAvailableIncrementReserved_conditionalFail_throwsConflictException() {
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        ConditionalCheckFailedException.builder().message("failed").build()));

        StepVerifier.create(adapter.decrementAvailableIncrementReserved(eventId, ttId, 2, 0L))
                .expectError(ConflictException.class)
                .verify();
    }

    // --- decrementReservedIncrementSold ---

    @Test
    void decrementReservedIncrementSold_success_returnsCounter() {
        Map<String, AttributeValue> attrs = Map.of(
                "eventId", s(eventId.toString()), "ticketTypeId", s(ttId.toString()),
                "availableCount", n(8), "reservedCount", n(0), "soldCount", n(2), "version", n(2));
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        UpdateItemResponse.builder().attributes(attrs).build()));

        StepVerifier.create(adapter.decrementReservedIncrementSold(eventId, ttId, 2, 1L))
                .expectNextMatches(c -> c.soldCount() == 2)
                .verifyComplete();
    }

    @Test
    void decrementReservedIncrementSold_conditionalFail_throwsConflictException() {
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        ConditionalCheckFailedException.builder().message("failed").build()));

        StepVerifier.create(adapter.decrementReservedIncrementSold(eventId, ttId, 2, 1L))
                .expectError(ConflictException.class)
                .verify();
    }

    // --- decrementReservedIncrementAvailable ---

    @Test
    void decrementReservedIncrementAvailable_success_returnsCounter() {
        Map<String, AttributeValue> attrs = Map.of(
                "eventId", s(eventId.toString()), "ticketTypeId", s(ttId.toString()),
                "availableCount", n(10), "reservedCount", n(0), "soldCount", n(0), "version", n(2));
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        UpdateItemResponse.builder().attributes(attrs).build()));

        StepVerifier.create(adapter.decrementReservedIncrementAvailable(eventId, ttId, 2, 1L))
                .expectNextMatches(c -> c.availableCount() == 10)
                .verifyComplete();
    }

    @Test
    void decrementReservedIncrementAvailable_conditionalFail_throwsConflictException() {
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        ConditionalCheckFailedException.builder().message("failed").build()));

        StepVerifier.create(adapter.decrementReservedIncrementAvailable(eventId, ttId, 2, 1L))
                .expectError(ConflictException.class)
                .verify();
    }

    // --- decrementReservedForComplimentary ---

    @Test
    void decrementReservedForComplimentary_success_returnsCounter() {
        Map<String, AttributeValue> attrs = Map.of(
                "eventId", s(eventId.toString()), "ticketTypeId", s(ttId.toString()),
                "availableCount", n(8), "reservedCount", n(0), "soldCount", n(0), "version", n(2));
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        UpdateItemResponse.builder().attributes(attrs).build()));

        StepVerifier.create(adapter.decrementReservedForComplimentary(eventId, ttId, 2, 1L))
                .expectNextMatches(c -> c.reservedCount() == 0)
                .verifyComplete();
    }

    @Test
    void decrementReservedForComplimentary_conditionalFail_throwsConflictException() {
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        ConditionalCheckFailedException.builder().message("failed").build()));

        StepVerifier.create(adapter.decrementReservedForComplimentary(eventId, ttId, 2, 1L))
                .expectError(ConflictException.class)
                .verify();
    }
}
