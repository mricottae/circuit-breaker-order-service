package com.mricotta.circuitbreaker.order.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> fieldErrors) {

    public static ErrorResponse of(HttpStatus status, String message, String path) {
        return of(status, message, path, Map.of());
    }

    public static ErrorResponse of(
            HttpStatus status, String message, String path, Map<String, String> fieldErrors) {
        return new ErrorResponse(
                Instant.now(), status.value(), status.getReasonPhrase(), message, path, fieldErrors);
    }
}
