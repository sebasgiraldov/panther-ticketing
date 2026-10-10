package com.panther.ticketing.api;

import com.panther.ticketing.api.handlers.ApiHandler;
import com.panther.ticketing.model.enums.OrderStatus;
import com.panther.ticketing.model.enums.ReservationStatus;
import com.panther.ticketing.model.event.Event;
import com.panther.ticketing.model.event.EventDetails;
import com.panther.ticketing.model.event.InventoryCounter;
import com.panther.ticketing.model.event.TicketType;
import com.panther.ticketing.model.order.Order;
import com.panther.ticketing.model.reservation.Reservation;
import com.panther.ticketing.ports.in.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiHandlerTest {

    @Mock ICreateEvent createEvent;
    @Mock IGetEvent getEvent;
    @Mock ICheckAvailability checkAvailability;
    @Mock IReserveTickets reserveTickets;
    @Mock IPurchaseTickets purchaseTickets;
    @Mock IGetOrderStatus getOrderStatus;
    @Mock IMarkComplimentary markComplimentary;

    WebTestClient webTestClient;

    static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 1, 10, 0);
    static final UUID EVENT_ID = UUID.randomUUID();
    static final UUID TT_ID = UUID.randomUUID();
    static final UUID RESERVATION_ID = UUID.randomUUID();
    static final UUID ORDER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ApiHandler handler = new ApiHandler(createEvent, getEvent, checkAvailability,
                reserveTickets, purchaseTickets, getOrderStatus, markComplimentary);
        RouterRest router = new RouterRest();
        webTestClient = WebTestClient.bindToRouterFunction(router.routes(handler)).build();
    }

    @Test
    void createEvent_returns201() {
        Event event = new Event(EVENT_ID, "Concert", "Desc", NOW, "Venue", 10, NOW);
        when(createEvent.execute(any(), any())).thenReturn(Mono.just(event));

        webTestClient.post().uri("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":"Concert","description":"Desc","date":"2026-12-01T20:00:00",
                        "venue":"Venue","totalCapacity":10,
                        "ticketTypes":[{"name":"General","price":50000,"totalQuantity":10}]}
                        """)
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void getEvent_returns200() {
        Event event = new Event(EVENT_ID, "Concert", "Desc", NOW, "Venue", 10, NOW);
        TicketType tt = new TicketType(TT_ID, EVENT_ID, "General", BigDecimal.valueOf(50), 10);
        InventoryCounter counter = new InventoryCounter(EVENT_ID, TT_ID, 10, 0, 0, 0L);
        EventDetails details = new EventDetails(event, List.of(new EventDetails.TicketTypeAvailability(tt, counter)));
        when(getEvent.execute(EVENT_ID)).thenReturn(Mono.just(details));

        webTestClient.get().uri("/api/v1/events/{eventId}", EVENT_ID)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void checkAvailability_returns200() {
        InventoryCounter counter = new InventoryCounter(EVENT_ID, TT_ID, 8, 2, 0, 0L);
        when(checkAvailability.execute(EVENT_ID, TT_ID)).thenReturn(Mono.just(counter));

        webTestClient.get()
                .uri("/api/v1/events/{eventId}/availability?ticketTypeId={ttId}", EVENT_ID, TT_ID)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void reserveTickets_returns201() {
        Reservation reservation = new Reservation(RESERVATION_ID, EVENT_ID, TT_ID,
                List.of(UUID.randomUUID()), "customer", NOW.plusMinutes(10), ReservationStatus.ACTIVE, 0L, NOW);
        when(reserveTickets.execute(any(), any(), anyInt(), anyString())).thenReturn(Mono.just(reservation));

        webTestClient.post().uri("/api/v1/events/{eventId}/reservations", EVENT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"ticketTypeId":"%s","quantity":2,"customerRef":"customer"}
                        """.formatted(TT_ID))
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void purchaseTickets_returns202() {
        Order order = new Order(ORDER_ID, RESERVATION_ID, TT_ID, EVENT_ID, "customer", 2,
                BigDecimal.valueOf(100), OrderStatus.PENDING, "key", 0L, NOW, NOW);
        when(purchaseTickets.execute(any(), anyString(), any())).thenReturn(Mono.just(order));

        webTestClient.post().uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"reservationId":"%s","customerRef":"customer","idempotencyKey":"key"}
                        """.formatted(RESERVATION_ID))
                .exchange()
                .expectStatus().isAccepted();
    }

    @Test
    void getOrderStatus_returns200() {
        Order order = new Order(ORDER_ID, RESERVATION_ID, TT_ID, EVENT_ID, "customer", 2,
                BigDecimal.valueOf(100), OrderStatus.CONFIRMED, "key", 0L, NOW, NOW);
        when(getOrderStatus.execute(ORDER_ID)).thenReturn(Mono.just(order));

        webTestClient.get().uri("/api/v1/orders/{orderId}", ORDER_ID)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void markComplimentary_returns200() {
        Reservation confirmed = new Reservation(RESERVATION_ID, EVENT_ID, TT_ID,
                List.of(UUID.randomUUID()), "vip", NOW.plusMinutes(10), ReservationStatus.CONFIRMED, 0L, NOW);
        when(markComplimentary.execute(any(), any(), anyString())).thenReturn(Mono.just(confirmed));

        webTestClient.post().uri("/api/v1/events/{eventId}/complimentary", EVENT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"reservationId":"%s","ownerRef":"vip"}
                        """.formatted(RESERVATION_ID))
                .exchange()
                .expectStatus().isOk();
    }
}
