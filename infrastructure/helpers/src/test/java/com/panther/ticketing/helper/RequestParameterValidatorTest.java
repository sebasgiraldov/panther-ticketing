package com.panther.ticketing.helper;

import com.panther.ticketing.model.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequestParameterValidatorTest {

    static class Validator extends RequestParameterValidator {
        public void testPathVariables(ServerRequest request, List<String> required) {
            validatePathVariables(request, required);
        }
        public void testQueryParams(ServerRequest request, List<String> required) {
            validateQueryParams(request, required);
        }
    }

    @Mock ServerRequest request;

    Validator validator;

    @BeforeEach
    void setUp() {
        validator = new Validator();
    }

    @Test
    void validatePathVariables_present_doesNotThrow() {
        when(request.pathVariable("eventId")).thenReturn("abc-123");
        assertDoesNotThrow(() -> validator.testPathVariables(request, List.of("eventId")));
    }

    @Test
    void validatePathVariables_blank_throwsBusinessException() {
        when(request.pathVariable("eventId")).thenReturn("  ");
        assertThrows(BusinessException.class,
                () -> validator.testPathVariables(request, List.of("eventId")));
    }

    @Test
    void validatePathVariables_null_throwsBusinessException() {
        when(request.pathVariable("eventId")).thenReturn(null);
        assertThrows(BusinessException.class,
                () -> validator.testPathVariables(request, List.of("eventId")));
    }

    @Test
    void validateQueryParams_present_doesNotThrow() {
        when(request.queryParam("ticketTypeId")).thenReturn(Optional.of("some-id"));
        assertDoesNotThrow(() -> validator.testQueryParams(request, List.of("ticketTypeId")));
    }

    @Test
    void validateQueryParams_missing_throwsBusinessException() {
        when(request.queryParam("ticketTypeId")).thenReturn(Optional.empty());
        assertThrows(BusinessException.class,
                () -> validator.testQueryParams(request, List.of("ticketTypeId")));
    }

    @Test
    void validateQueryParams_blank_throwsBusinessException() {
        when(request.queryParam("ticketTypeId")).thenReturn(Optional.of("  "));
        assertThrows(BusinessException.class,
                () -> validator.testQueryParams(request, List.of("ticketTypeId")));
    }
}
