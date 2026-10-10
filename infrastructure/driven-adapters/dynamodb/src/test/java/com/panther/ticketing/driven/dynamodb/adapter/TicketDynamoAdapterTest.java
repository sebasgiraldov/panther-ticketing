package com.panther.ticketing.driven.dynamodb.adapter;

import com.panther.ticketing.model.enums.TicketStatus;
import com.panther.ticketing.model.exception.ConflictException;
import com.panther.ticketing.model.exception.NotFoundException;
import com.panther.ticketing.model.ticket.Ticket;
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
class TicketDynamoAdapterTest {

    @Mock DynamoDbAsyncClient client;

    TicketDynamoAdapter adapter;

    UUID ticketId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID ttId = UUID.randomUUID();
    UUID reservationId = UUID.randomUUID();
    LocalDateTime now = LocalDateTime.of(2026, 1, 1, 10, 0);

    @BeforeEach
    void setUp() {
        adapter = new TicketDynamoAdapter(client, "tickets");
    }

    Ticket availableTicket() {
        return new Ticket(ticketId, eventId, ttId, TicketStatus.AVAILABLE,
                BigDecimal.valueOf(50), null, null, null, 0L, now, now);
    }

    Map<String, AttributeValue> ticketItem(TicketStatus status) {
        return Map.of(
                "id", s(ticketId.toString()), "eventId", s(eventId.toString()),
                "ticketTypeId", s(ttId.toString()), "status", s(status.name()),
                "price", n(50), "version", n(0L),
                "createdAt", s(formatDateTime(now)), "updatedAt", s(formatDateTime(now)));
    }

    // --- save ---

    @Test
    void save_persistsAndReturnsTicket() {
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(PutItemResponse.builder().build()));

        StepVerifier.create(adapter.save(availableTicket()))
                .expectNextMatches(t -> t.id().equals(ticketId))
                .verifyComplete();
    }

    // --- saveAll ---

    @Test
    void saveAll_savesEachTicket() {
        Ticket t2 = new Ticket(UUID.randomUUID(), eventId, ttId, TicketStatus.AVAILABLE,
                BigDecimal.valueOf(50), null, null, null, 0L, now, now);
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(PutItemResponse.builder().build()));

        StepVerifier.create(adapter.saveAll(List.of(availableTicket(), t2)))
                .expectNextCount(2)
                .verifyComplete();
    }

    // --- findById ---

    @Test
    void findById_found_returnsTicket() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        GetItemResponse.builder().item(ticketItem(TicketStatus.AVAILABLE)).build()));

        StepVerifier.create(adapter.findById(ticketId))
                .expectNextMatches(t -> t.status() == TicketStatus.AVAILABLE)
                .verifyComplete();
    }

    @Test
    void findById_notFound_throwsNotFoundException() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder().build()));

        StepVerifier.create(adapter.findById(ticketId))
                .expectError(NotFoundException.class)
                .verify();
    }

    // --- findByEventIdAndTicketTypeIdAndStatus ---

    @Test
    void findByEventIdAndTicketTypeIdAndStatus_returnsFlux() {
        when(client.query(any(QueryRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        QueryResponse.builder().items(List.of(ticketItem(TicketStatus.AVAILABLE))).build()));

        StepVerifier.create(adapter.findByEventIdAndTicketTypeIdAndStatus(eventId, ttId, TicketStatus.AVAILABLE, 1))
                .expectNextCount(1)
                .verifyComplete();
    }

    // --- findByReservationId ---

    @Test
    void findByReservationId_returnsFlux() {
        Map<String, AttributeValue> item = Map.of(
                "id", s(ticketId.toString()), "eventId", s(eventId.toString()),
                "ticketTypeId", s(ttId.toString()), "status", s("RESERVED"),
                "price", n(50), "version", n(0L), "reservationId", s(reservationId.toString()),
                "createdAt", s(formatDateTime(now)), "updatedAt", s(formatDateTime(now)));
        when(client.query(any(QueryRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        QueryResponse.builder().items(List.of(item)).build()));

        StepVerifier.create(adapter.findByReservationId(reservationId))
                .expectNextMatches(t -> t.reservationId().equals(reservationId))
                .verifyComplete();
    }

    // --- updateStatusConditional ---

    @Test
    void updateStatusConditional_success_returnsUpdatedTicket() {
        Ticket reserved = availableTicket().reserve("customer", reservationId);
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(UpdateItemResponse.builder().build()));

        StepVerifier.create(adapter.updateStatusConditional(reserved, TicketStatus.AVAILABLE))
                .expectNextMatches(t -> t.status() == TicketStatus.RESERVED && t.version() == 1L)
                .verifyComplete();
    }

    @Test
    void updateStatusConditional_conditionalFail_throwsConflictException() {
        Ticket reserved = availableTicket().reserve("customer", reservationId);
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        ConditionalCheckFailedException.builder().message("failed").build()));

        StepVerifier.create(adapter.updateStatusConditional(reserved, TicketStatus.AVAILABLE))
                .expectError(ConflictException.class)
                .verify();
    }

    @Test
    void updateStatusConditional_nullOwnerAndReservation_handlesNulls() {
        Ticket released = new Ticket(ticketId, eventId, ttId, TicketStatus.AVAILABLE,
                BigDecimal.valueOf(50), null, null, null, 0L, now, now);
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(UpdateItemResponse.builder().build()));

        StepVerifier.create(adapter.updateStatusConditional(released, TicketStatus.RESERVED))
                .expectNextMatches(t -> t.ownerRef() == null && t.reservationId() == null)
                .verifyComplete();
    }
}
