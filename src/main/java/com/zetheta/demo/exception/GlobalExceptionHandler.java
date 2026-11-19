package com.zetheta.demo.exception;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Hidden
@RestControllerAdvice(basePackages = "com.zetheta.demo.controllers")
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> handleBusinessRuleError(IllegalArgumentException ex) {
        return buildErrorResponse("BUSINESS_RULE_VIOLATION", "TXN_422", ex.getMessage(), HttpStatus.valueOf(422));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Object> handleConflictError(IllegalStateException ex) {
        return buildErrorResponse("CONFLICT", "TXN_409", ex.getMessage(), HttpStatus.CONFLICT);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleGenericError(Exception ex) {
        return buildErrorResponse("INTERNAL_ERROR", "SYS_500", ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<Object> buildErrorResponse(String type, String code, String message, HttpStatus status) {
        Map<String, Object> errorDetails = new LinkedHashMap<>();
        errorDetails.put("type", type);
        errorDetails.put("code", code);
        errorDetails.put("message", message);
        errorDetails.put("request_id", "req_" + UUID.randomUUID());
        errorDetails.put("timestamp", Instant.now().toString());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", errorDetails);
        return ResponseEntity.status(status).body(body);
    }
}