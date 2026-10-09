package com.panther.ticketing.helper;

import com.panther.ticketing.model.exception.BusinessException;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.util.List;

public abstract class RequestParameterValidator {

    protected void validatePathVariables(ServerRequest request, List<String> required) {
        for (String var : required) {
            String value = request.pathVariable(var);
            if (value == null || value.isBlank()) {
                throw new BusinessException("Required path variable missing: " + var);
            }
        }
    }

    protected void validateQueryParams(ServerRequest request, List<String> required) {
        for (String param : required) {
            String value = request.queryParam(param).orElse(null);
            if (value == null || value.isBlank()) {
                throw new BusinessException("Required query parameter missing: " + param);
            }
        }
    }
}
