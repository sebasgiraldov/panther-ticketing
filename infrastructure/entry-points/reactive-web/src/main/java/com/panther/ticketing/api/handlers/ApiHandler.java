package com.panther.ticketing.api.handlers;

import com.panther.ticketing.api.dto.*;
import com.panther.ticketing.api.mapper.ApiMapper;
import com.panther.ticketing.helper.RequestParameterValidator;
import com.panther.ticketing.ports.in.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@Component
public class ApiHandler extends RequestParameterValidator {

    private final ICreateEvent createEvent;
    private final IGetEvent getEvent;
    private final ICheckAvailability checkAvailability;
    private final IReserveTickets reserveTickets;
    private final IPurchaseTickets purchaseTickets;
    private final IGetOrderStatus getOrderStatus;
    private final IMarkComplimentary markComplimentary;

    public ApiHandler(ICreateEvent createEvent, IGetEvent getEvent,
                      ICheckAvailability checkAvailability, IReserveTickets reserveTickets,
                      IPurchaseTickets purchaseTickets, IGetOrderStatus getOrderStatus,
                      IMarkComplimentary markComplimentary) {
        this.createEvent = createEvent;
        this.getEvent = getEvent;
        this.checkAvailability = checkAvailability;
        this.reserveTickets = reserveTickets;
        this.purchaseTickets = purchaseTickets;
        this.getOrderStatus = getOrderStatus;
        this.markComplimentary = markComplimentary;
    }

    public Mono<ServerResponse> createEvent(ServerRequest request) {
        return request.bodyToMono(CreateEventRequest.class)
                .flatMap(req -> createEvent.execute(ApiMapper.toEvent(req), ApiMapper.toTicketTypes(req)))
                .map(ApiMapper::toEventResponse)
                .flatMap(resp -> ServerResponse.status(HttpStatus.CREATED).bodyValue(resp));
    }

    public Mono<ServerResponse> getEvent(ServerRequest request) {
        validatePathVariables(request, List.of("eventId"));
        UUID eventId = UUID.fromString(request.pathVariable("eventId"));
        return getEvent.execute(eventId)
                .map(ApiMapper::toEventResponse)
                .flatMap(resp -> ServerResponse.ok().bodyValue(resp));
    }

    public Mono<ServerResponse> checkAvailability(ServerRequest request) {
        validatePathVariables(request, List.of("eventId"));
        validateQueryParams(request, List.of("ticketTypeId"));
        UUID eventId = UUID.fromString(request.pathVariable("eventId"));
        UUID ticketTypeId = UUID.fromString(request.queryParam("ticketTypeId").orElseThrow());
        return checkAvailability.execute(eventId, ticketTypeId)
                .map(ApiMapper::toAvailabilityResponse)
                .flatMap(resp -> ServerResponse.ok().bodyValue(resp));
    }

    public Mono<ServerResponse> reserveTickets(ServerRequest request) {
        validatePathVariables(request, List.of("eventId"));
        UUID eventId = UUID.fromString(request.pathVariable("eventId"));
        return request.bodyToMono(ReserveTicketsRequest.class)
                .flatMap(req -> reserveTickets.execute(
                        eventId, UUID.fromString(req.ticketTypeId()), req.quantity(), req.customerRef()))
                .map(ApiMapper::toReservationResponse)
                .flatMap(resp -> ServerResponse.status(HttpStatus.CREATED).bodyValue(resp));
    }

    public Mono<ServerResponse> purchaseTickets(ServerRequest request) {
        return request.bodyToMono(PurchaseRequest.class)
                .flatMap(req -> purchaseTickets.execute(
                        UUID.fromString(req.reservationId()), req.customerRef(), req.idempotencyKey()))
                .map(ApiMapper::toOrderResponse)
                .flatMap(resp -> ServerResponse.status(HttpStatus.ACCEPTED).bodyValue(resp));
    }

    public Mono<ServerResponse> getOrderStatus(ServerRequest request) {
        validatePathVariables(request, List.of("orderId"));
        UUID orderId = UUID.fromString(request.pathVariable("orderId"));
        return getOrderStatus.execute(orderId)
                .map(ApiMapper::toOrderResponse)
                .flatMap(resp -> ServerResponse.ok().bodyValue(resp));
    }

    public Mono<ServerResponse> markComplimentary(ServerRequest request) {
        validatePathVariables(request, List.of("ticketId"));
        UUID ticketId = UUID.fromString(request.pathVariable("ticketId"));
        return request.bodyToMono(MarkComplimentaryRequest.class)
                .flatMap(req -> markComplimentary.execute(ticketId, req.ownerRef()))
                .map(ApiMapper::toTicketResponse)
                .flatMap(resp -> ServerResponse.ok().bodyValue(resp));
    }
}
