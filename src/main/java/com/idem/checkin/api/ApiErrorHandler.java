package com.idem.checkin.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException error) {
        return body(error.status(), error.code(), error.getMessage(), error.details());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, HandlerMethodValidationException.class})
    ResponseEntity<Map<String, Object>> badRequest(Exception error) {
        String message = error instanceof MethodArgumentNotValidException invalid
                ? invalid.getBindingResult().getFieldErrors().stream()
                        .map(field -> field.getField() + ": " + field.getDefaultMessage())
                        .sorted().reduce((a, b) -> a + "; " + b).orElse("Invalid request")
                : "Invalid request";
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception error) {
        if (error instanceof ErrorResponse known) {
            HttpStatus status = HttpStatus.valueOf(known.getStatusCode().value());
            return body(status, status.name(), status.getReasonPhrase(), null);
        }
        log.error("Unexpected error: {}", error.getClass().getName(), error);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal error", null);
    }

    static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message, Object details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        if (details != null) {
            body.put("details", details);
        }
        return ResponseEntity.status(status).body(body);
    }
}
