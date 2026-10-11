package com.panther.ticketing.api.error;

import com.panther.ticketing.model.exception.DomainException;
import com.panther.ticketing.model.exception.BusinessException;
import com.panther.ticketing.model.exception.ConflictException;
import com.panther.ticketing.model.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@Component
@Order(-2)
public class GlobalErrorHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

    private final ObjectMapper objectMapper;

    public GlobalErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        // Unwrap Reactor wrapper exceptions (RetryExhaustedException, etc.) to get the root cause
        Throwable cause = ex;
        while (cause.getCause() != null && !(cause instanceof DomainException)) {
            cause = cause.getCause();
        }

        if (cause instanceof DomainException) {
            log.warn("Business error on {}: {}", exchange.getRequest().getPath(), cause.getMessage());
        } else {
            log.error("Unhandled error on {}: {}", exchange.getRequest().getPath(), cause.getMessage());
        }

        HttpStatus status = switch (cause) {
            case NotFoundException e -> HttpStatus.NOT_FOUND;
            case ConflictException e -> HttpStatus.CONFLICT;
            case BusinessException e -> HttpStatus.BAD_REQUEST;
            case DomainException e -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };

        Map<String, Object> body = Map.of(
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", cause.getMessage() != null ? cause.getMessage() : "Unexpected error"
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
