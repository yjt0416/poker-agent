package com.agenttavern.web;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/tables")
class GameTableController {
    static final String SESSION_COOKIE = "agent_tavern_session";
    private final GameTableService tables;
    private final MeterRegistry metrics;
    @Value("${agent-tavern.secure-cookie:false}") private boolean secureCookie;

    GameTableController(GameTableService tables, MeterRegistry metrics) {
        this.tables = tables;
        this.metrics = metrics;
    }

    @PostMapping
    TableView create(@RequestBody(required = false) CreateTableRequest request, HttpServletResponse response) {
        GameTableService.CreatedTable created = tables.createTable(
                request == null ? null : request.displayName(),
                request == null ? null : request.mode(), request == null ? null : request.personas());
        metrics.counter("agent.tavern.tables.created", "mode", created.view().mode()).increment();
        ResponseCookie cookie = ResponseCookie.from(SESSION_COOKIE, created.sessionToken())
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/api")
                .maxAge(Duration.ofHours(8))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return created.view();
    }

    @GetMapping("/current")
    TableView current(@CookieValue(name = SESSION_COOKIE, required = false) String token) {
        return tables.current(token);
    }

    @PostMapping("/current/actions")
    TableView act(
            @CookieValue(name = SESSION_COOKIE, required = false) String token,
            @RequestBody PlayerActionRequest request) {
        return tables.act(token, request);
    }

    @PostMapping("/current/chat")
    TableView talk(
            @CookieValue(name = SESSION_COOKIE, required = false) String token,
            @RequestBody TableTalkRequest request) {
        return tables.talk(token, request);
    }

    @PostMapping("/current/next-hand")
    TableView nextHand(@CookieValue(name = SESSION_COOKIE, required = false) String token, @RequestBody TableCommand command) {
        return tables.nextHand(token, command);
    }

    @PostMapping("/current/advance")
    TableView advance(@CookieValue(name = SESSION_COOKIE, required = false) String token, @RequestBody TableCommand command) {
        return tables.advanceSpectator(token, command);
    }

    @GetMapping(value = "/current/events", produces = "text/event-stream")
    SseEmitter events(@CookieValue(name = SESSION_COOKIE, required = false) String token,
            @RequestParam String tableId, @RequestParam(defaultValue = "0") long after,
            @RequestHeader(name = "Last-Event-ID", required = false) Long lastId, HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-store");
        SseEmitter stream = tables.subscribe(token, tableId, lastId == null ? after : lastId);
        metrics.counter("agent.tavern.sse.subscriptions").increment();
        if (lastId != null || after > 1) metrics.counter("agent.tavern.sse.resumes").increment();
        return stream;
    }

    @GetMapping("/current/replay")
    GameTableService.ReplayPage replay(@CookieValue(name = SESSION_COOKIE, required = false) String token,
            @RequestParam String tableId, @RequestParam(defaultValue = "0") long after) {
        return tables.replay(token, tableId, after);
    }

    @GetMapping("/roster")
    java.util.List<com.agenttavern.agents.AgentPersona> roster() { return com.agenttavern.agents.AgentRoster.all(); }
}
