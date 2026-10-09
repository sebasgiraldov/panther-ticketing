package com.panther.ticketing.api;

import com.panther.ticketing.api.handlers.ApiHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.*;

@Configuration
public class RouterRest {

    private static final String BASE = "/api/v1";

    @Bean
    public RouterFunction<ServerResponse> routes(ApiHandler handler) {
        return RouterFunctions.route()
                .POST(BASE + "/events", handler::createEvent)
                .GET(BASE + "/events/{eventId}", handler::getEvent)
                .GET(BASE + "/events/{eventId}/availability", handler::checkAvailability)
                .POST(BASE + "/events/{eventId}/reservations", handler::reserveTickets)
                .POST(BASE + "/orders", handler::purchaseTickets)
                .GET(BASE + "/orders/{orderId}", handler::getOrderStatus)
                .POST(BASE + "/tickets/{ticketId}/complimentary", handler::markComplimentary)
                .build();
    }
}
