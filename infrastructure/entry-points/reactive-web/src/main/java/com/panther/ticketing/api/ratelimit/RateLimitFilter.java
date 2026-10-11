package com.panther.ticketing.api.ratelimit;

import io.github.resilience4j.ratelimiter.RateLimiter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * WebFilter that enforces rate limits on the two highest-risk write endpoints:
 *
 * <ul>
 *   <li>POST /api/v1/events/{eventId}/reservations — prevents inventory exhaustion via
 *       flood of reservations that expire without purchase.</li>
 *   <li>POST /api/v1/orders — prevents duplicate-order spam and queue flooding.</li>
 * </ul>
 *
 * <p>Limits are shared across all callers (global, not per-IP). In a multi-replica
 * deployment each instance maintains its own in-memory counter; a Redis-backed
 * RateLimiter would be needed for a truly distributed limit.
 *
 * <p>Returns 429 Too Many Requests with a JSON body consistent with GlobalErrorHandler
 * when the limit is exceeded.
 */
@Component
@Order(-1)
public class RateLimitFilter implements WebFilter {

    private static final String RESERVATIONS_PATH = "/api/v1/events/";
    private static final String RESERVATIONS_SUFFIX = "/reservations";
    private static final String ORDERS_PATH = "/api/v1/orders";

    private final RateLimiter reservationsRateLimiter;
    private final RateLimiter ordersRateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(@Qualifier("reservationsRateLimiter") RateLimiter reservationsRateLimiter,
                           @Qualifier("ordersRateLimiter") RateLimiter ordersRateLimiter,
                           ObjectMapper objectMapper) {
        this.reservationsRateLimiter = reservationsRateLimiter;
        this.ordersRateLimiter = ordersRateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String method = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getPath().value();

        if ("POST".equals(method)) {
            if (path.startsWith(RESERVATIONS_PATH) && path.endsWith(RESERVATIONS_SUFFIX)) {
                return applyLimit(exchange, chain, reservationsRateLimiter);
            }
            if (ORDERS_PATH.equals(path)) {
                return applyLimit(exchange, chain, ordersRateLimiter);
            }
        }

        return chain.filter(exchange);
    }

    private Mono<Void> applyLimit(ServerWebExchange exchange, WebFilterChain chain, RateLimiter limiter) {
        if (limiter.acquirePermission()) {
            return chain.filter(exchange);
        }
        return writeRateLimitedResponse(exchange);
    }

    private Mono<Void> writeRateLimitedResponse(ServerWebExchange exchange) {
        HttpStatus status = HttpStatus.TOO_MANY_REQUESTS;
        Map<String, Object> body = Map.of(
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", "Too many requests — rate limit exceeded. Please slow down."
        );
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return Mono.fromCallable(() -> objectMapper.writeValueAsBytes(body))
                .flatMap(bytes -> {
                    DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
                    return exchange.getResponse().writeWith(Mono.just(buffer));
                });
    }
}
