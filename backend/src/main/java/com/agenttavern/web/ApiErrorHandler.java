package com.agenttavern.web;

import com.agenttavern.tablechat.ChatRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiErrorHandler {
    @ExceptionHandler({java.util.ConcurrentModificationException.class,
            com.agenttavern.tournament.application.ConcurrentTournamentUpdateException.class})
    ResponseEntity<?> conflictError(RuntimeException exception, HttpServletRequest request) {
        return error(409, "牌局已更新，请同步后重试", request);
    }
    @ExceptionHandler(TableSessionException.class)
    ResponseEntity<?> tableError(TableSessionException exception, HttpServletRequest request) {
        return error(exception.status(), exception.getMessage(), request);
    }

    @ExceptionHandler(ChatRejectedException.class)
    ResponseEntity<?> chatError(ChatRejectedException exception, HttpServletRequest request) {
        return error(HttpStatus.TOO_MANY_REQUESTS.value(), exception.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> validationError(IllegalArgumentException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST.value(), exception.getMessage(), request);
    }

    private static ResponseEntity<?> error(int status, String message, HttpServletRequest request) {
        if (request.getRequestURI().endsWith("/events")) return ResponseEntity.status(status).build();
        return ResponseEntity.status(status).body(Map.of(
                "status", status,
                "message", message == null ? "请求失败" : message,
                "timestamp", Instant.now().toString()));
    }
}
