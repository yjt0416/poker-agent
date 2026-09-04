package com.agenttavern.web;

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

@RestController
@RequestMapping("/api/tables")
class GameTableController {
    static final String SESSION_COOKIE = "agent_tavern_session";
    private final GameTableService tables;

    GameTableController(GameTableService tables) {
        this.tables = tables;
    }

    @PostMapping
    TableView create(@RequestBody(required = false) CreateTableRequest request, HttpServletResponse response) {
        GameTableService.CreatedTable created = tables.createTable(
                request == null ? null : request.displayName(),
                request == null ? null : request.mode());
        ResponseCookie cookie = ResponseCookie.from(SESSION_COOKIE, created.sessionToken())
                .httpOnly(true)
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
        return tables.talk(token, request == null ? null : request.text());
    }

    @PostMapping("/current/next-hand")
    TableView nextHand(@CookieValue(name = SESSION_COOKIE, required = false) String token) {
        return tables.nextHand(token);
    }

    @PostMapping("/current/advance")
    TableView advance(@CookieValue(name = SESSION_COOKIE, required = false) String token) {
        return tables.advanceSpectator(token);
    }
}
