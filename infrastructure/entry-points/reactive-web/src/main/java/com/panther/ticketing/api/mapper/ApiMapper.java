package com.panther.ticketing.api.mapper;

import com.panther.ticketing.api.dto.*;
import com.panther.ticketing.model.event.Event;
import com.panther.ticketing.model.event.EventDetails;
import com.panther.ticketing.model.event.InventoryCounter;
import com.panther.ticketing.model.event.TicketType;
import com.panther.ticketing.model.order.Order;
import com.panther.ticketing.model.reservation.Reservation;
import com.panther.ticketing.model.ticket.Ticket;

import java.util.List;
import java.util.UUID;

public final class ApiMapper {

    private ApiMapper() {}

    public static Event toEvent(CreateEventRequest req) {
        return new Event(UUID.randomUUID(), req.name(), req.description(),
                req.date(), req.venue(), req.totalCapacity(), null);
    }

    public static List<TicketType> toTicketTypes(CreateEventRequest req) {
        return req.ticketTypes().stream()
                .map(tt -> new TicketType(UUID.randomUUID(), null, tt.name(), tt.price(), tt.totalQuantity()))
                .toList();
    }

    public static EventResponse toEventResponse(Event event) {
        return new EventResponse(event.id(), event.name(), event.description(),
                event.date(), event.venue(), event.totalCapacity(), event.createdAt(), List.of());
    }

    public static EventResponse toEventResponse(EventDetails details) {
        Event event = details.event();
        List<TicketTypeAvailabilityResponse> ticketTypes = details.ticketTypes().stream()
                .map(tta -> {
                    TicketType tt = tta.ticketType();
                    InventoryCounter counter = tta.availability();
                    return new TicketTypeAvailabilityResponse(
                            tt.id(), tt.name(), tt.price(), tt.totalQuantity(),
                            counter.availableCount(), counter.reservedCount(), counter.soldCount());
                })
                .toList();
        return new EventResponse(event.id(), event.name(), event.description(),
                event.date(), event.venue(), event.totalCapacity(), event.createdAt(), ticketTypes);
    }

    public static AvailabilityResponse toAvailabilityResponse(InventoryCounter counter) {
        return new AvailabilityResponse(counter.eventId(), counter.ticketTypeId(),
                counter.availableCount(), counter.reservedCount(), counter.soldCount());
    }

    public static ReservationResponse toReservationResponse(Reservation reservation) {
        return new ReservationResponse(reservation.id(), reservation.eventId(),
                reservation.ticketTypeId(), reservation.ticketIds(),
                reservation.customerRef(), reservation.expiresAt(), reservation.status());
    }

    public static OrderResponse toOrderResponse(Order order) {
        return new OrderResponse(order.id(), order.reservationId(), order.customerRef(),
                order.quantity(), order.totalAmount(), order.status(),
                order.createdAt(), order.updatedAt());
    }

    public static TicketResponse toTicketResponse(Ticket ticket) {
        return new TicketResponse(ticket.id(), ticket.eventId(), ticket.ticketTypeId(),
                ticket.status(), ticket.price(), ticket.seatNumber(), ticket.ownerRef());
    }
}
