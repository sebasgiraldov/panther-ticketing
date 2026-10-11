package com.panther.ticketing.api.ratelimit;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RateLimiter limiterWithCapacity(int permits) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(permits)
                .limitRefreshPeriod(Duration.ofSeconds(60))
                .timeoutDuration(Duration.ZERO)
                .build();
        return RateLimiterRegistry.of(config).rateLimiter("test-" + permits + "-" + System.nanoTime());
    }

    @Test
    void reservations_withinLimit_passes() {
        RateLimitFilter filter = new RateLimitFilter(limiterWithCapacity(10), limiterWithCapacity(10), objectMapper);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/events/abc/reservations").build());
        WebFilterChain chain = mock(WebFilterChain.class);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void reservations_exceedsLimit_returns429WithJsonBody() {
        RateLimiter exhausted = limiterWithCapacity(1);
        exhausted.acquirePermission();
        RateLimitFilter filter = new RateLimitFilter(exhausted, limiterWithCapacity(10), objectMapper);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/events/abc/reservations").build());
        WebFilterChain chain = mock(WebFilterChain.class);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getContentType())
                .hasToString("application/json");
    }

    @Test
    void orders_exceedsLimit_returns429() {
        RateLimiter exhausted = limiterWithCapacity(1);
        exhausted.acquirePermission();
        RateLimitFilter filter = new RateLimitFilter(limiterWithCapacity(10), exhausted, objectMapper);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/orders").build());
        WebFilterChain chain = mock(WebFilterChain.class);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void nonRateLimitedPath_alwaysPasses() {
        RateLimiter exhausted = limiterWithCapacity(1);
        exhausted.acquirePermission();
        RateLimitFilter filter = new RateLimitFilter(exhausted, exhausted, objectMapper);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/events").build());
        WebFilterChain chain = mock(WebFilterChain.class);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void postToNonSensitivePath_alwaysPasses() {
        RateLimiter exhausted = limiterWithCapacity(1);
        exhausted.acquirePermission();
        RateLimitFilter filter = new RateLimitFilter(exhausted, exhausted, objectMapper);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/events").build());
        WebFilterChain chain = mock(WebFilterChain.class);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
