package com.agenttavern.llmprovider;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenttavern.agents.AgentDecision;
import com.agenttavern.agents.AgentObservation;
import com.agenttavern.agents.AgentRoster;
import com.agenttavern.game.betting.ActionType;
import com.agenttavern.game.betting.LegalActions;
import com.agenttavern.game.betting.PlayerAction;
import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.betting.Street;
import com.agenttavern.game.card.Card;
import com.agenttavern.game.card.Rank;
import com.agenttavern.game.card.Suit;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DeepSeekDecisionProviderTest {
    @Test
    void parsesLegalJsonDecisionAndUsesBearerHeader() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (TestServer server = server(exchange -> {
            calls.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer secret-test-key");
            return completion("{\"action\":\"CALL\",\"amount\":0,\"tableTalk\":\"跟上。\","
                    + "\"emotion\":\"SUSPICIOUS\",\"publicSummary\":\"价格合适。\",\"memoryUpdates\":[]}");
        })) {
            AgentDecision result = provider(server).decide(observation());
            assertThat(result.action()).isEqualTo(PlayerAction.call());
            assertThat(result.tableTalk()).isEqualTo("跟上。");
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void malformedThenIllegalResponsesUseOnlyOneRepairAndFallback() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (TestServer server = server(exchange -> calls.incrementAndGet() == 1
                ? completion("not-json")
                : completion("{\"action\":\"RAISE\",\"amount\":999999,\"tableTalk\":\"\","
                + "\"emotion\":\"CALM\",\"publicSummary\":\"\",\"memoryUpdates\":[]}"))) {
            AgentDecision result = provider(server).decide(observation());
            assertThat(result.action()).isEqualTo(PlayerAction.fold());
            assertThat(calls).hasValue(2);
        }
    }

    @Test
    void propertiesNeverPrintSecret() {
        DeepSeekProperties properties = new DeepSeekProperties(URI.create("https://api.deepseek.com"),
                "super-secret", "model", Duration.ofSeconds(1));
        assertThat(properties.toString()).doesNotContain("super-secret").contains("<redacted>");
    }

    private static DeepSeekDecisionProvider provider(TestServer server) {
        return new DeepSeekDecisionProvider(new DeepSeekProperties(server.uri(), "secret-test-key", "test-model",
                Duration.ofSeconds(2)), HttpClient.newHttpClient(), new JsonMapper());
    }

    private static AgentObservation observation() {
        return new AgentObservation(new PlayerId(new UUID(0, 1)), AgentRoster.require("mirelle"),
                List.of(new Card(Suit.HEARTS, Rank.ACE), new Card(Suit.CLUBS, Rank.KING)), List.of(), List.of(),
                Street.PREFLOP, 150,
                new LegalActions(Set.of(ActionType.FOLD, ActionType.CALL, ActionType.ALL_IN),
                        50, OptionalLong.empty(), 1_000), List.of());
    }

    private static String completion(String content) throws Exception {
        return new JsonMapper().writeValueAsString(java.util.Map.of("choices", List.of(java.util.Map.of(
                "message", java.util.Map.of("content", content)))));
    }

    private static TestServer server(Responder responder) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try {
                byte[] response = responder.respond(exchange).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (Exception exception) {
                exchange.sendResponseHeaders(500, -1);
            } finally { exchange.close(); }
        });
        server.start();
        return new TestServer(server);
    }

    @FunctionalInterface private interface Responder { String respond(com.sun.net.httpserver.HttpExchange exchange) throws Exception; }
    private record TestServer(HttpServer server) implements AutoCloseable {
        URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
        @Override public void close() { server.stop(0); }
    }
}
