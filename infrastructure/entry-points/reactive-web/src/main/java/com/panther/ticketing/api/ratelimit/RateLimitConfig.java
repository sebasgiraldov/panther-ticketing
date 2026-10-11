package com.panther.ticketing.api.ratelimit;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RateLimitConfig {

    @Bean
    public RateLimiter reservationsRateLimiter(
            @Value("${rate-limit.reservations.limit-for-period}") int limitForPeriod,
            @Value("${rate-limit.reservations.refresh-period-seconds}") int refreshPeriodSeconds,
            @Value("${rate-limit.reservations.timeout-millis}") int timeoutMillis) {

        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(limitForPeriod)
                .limitRefreshPeriod(Duration.ofSeconds(refreshPeriodSeconds))
                .timeoutDuration(Duration.ofMillis(timeoutMillis))
                .build();

        return RateLimiterRegistry.of(config).rateLimiter("reservations");
    }

    @Bean
    public RateLimiter ordersRateLimiter(
            @Value("${rate-limit.orders.limit-for-period}") int limitForPeriod,
            @Value("${rate-limit.orders.refresh-period-seconds}") int refreshPeriodSeconds,
            @Value("${rate-limit.orders.timeout-millis}") int timeoutMillis) {

        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(limitForPeriod)
                .limitRefreshPeriod(Duration.ofSeconds(refreshPeriodSeconds))
                .timeoutDuration(Duration.ofMillis(timeoutMillis))
                .build();

        return RateLimiterRegistry.of(config).rateLimiter("orders");
    }
}
