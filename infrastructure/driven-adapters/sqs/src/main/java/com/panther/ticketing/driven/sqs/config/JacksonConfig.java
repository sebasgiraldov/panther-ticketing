package com.panther.ticketing.driven.sqs.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Explicitly provides a Jackson 3 ({@code tools.jackson}) {@link ObjectMapper}
 * for the SQS producer/consumer serialization.
 *
 * <p>Spring Boot 4 auto-configures a Jackson 3 ObjectMapper, but we declare it
 * explicitly here as a {@code @ConditionalOnMissingBean}-free, self-contained
 * bean so the SQS adapter never depends on web auto-configuration being present
 * (the adapter must work even in a non-web context, e.g. an isolated consumer).
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper sqsObjectMapper() {
        return new ObjectMapper();
    }
}
