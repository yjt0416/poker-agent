package com.agenttavern.web;

import com.agenttavern.tablechat.ChatRejectedException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiErrorHandler {
    @ExceptionHandler(TableSessionException.class)
    ResponseEntity<Map<String, Object>> tableError(TableSessionException exception) {
        return error(exception.status(), exception.getMessage());
    }

    @ExceptionHandler(ChatRejectedException.class)
    ResponseEntity<Map<String, Object>> chatError(ChatRejectedException exception) {
        return error(HttpStatus.TOO_MANY_REQUESTS.value(), exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> validationError(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST.value(), exception.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "status", status,
                "message", message == null ? "请求失败" : message,
                "timestamp", Instant.now().toString()));
    }
}
