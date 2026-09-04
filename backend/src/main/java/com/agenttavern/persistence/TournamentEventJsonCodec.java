package com.agenttavern.persistence;

import com.agenttavern.game.hand.HandEvent;
import com.agenttavern.tournament.TournamentEvent;
import com.agenttavern.tournament.TournamentId;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Explicit, stable JSON mapping for server-private persisted tournament events. The payloads are
 * not public event representations because {@code HoleCardsDealt} contains every player's hole
 * cards. No polymorphic default typing is used.
 */
public final class TournamentEventJsonCodec {

    private static final String TOURNAMENT_STARTED = "TOURNAMENT_STARTED";
    private static final String HAND_EVENT_RECORDED = "HAND_EVENT_RECORDED";
    private static final String PLAYER_ELIMINATED = "PLAYER_ELIMINATED";
    private static final String BLIND_LEVEL_ADVANCED = "BLIND_LEVEL_ADVANCED";
    private static final String TOURNAMENT_COMPLETED = "TOURNAMENT_COMPLETED";

    private final JsonMapper jsonMapper;

    public TournamentEventJsonCodec(JsonMapper jsonMapper) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    public EncodedEvent encode(TournamentEvent event) {
        Objects.requireNonNull(event, "event");
        return switch (event) {
            case TournamentEvent.TournamentStarted started ->
                    new EncodedEvent(TOURNAMENT_STARTED, write(started));
            case TournamentEvent.HandEventRecorded recorded ->
                    new EncodedEvent(HAND_EVENT_RECORDED, writeRecordedHandEvent(recorded));
            case TournamentEvent.PlayerEliminated eliminated ->
                    new EncodedEvent(PLAYER_ELIMINATED, write(eliminated));
            case TournamentEvent.BlindLevelAdvanced advanced ->
                    new EncodedEvent(BLIND_LEVEL_ADVANCED, write(advanced));
            case TournamentEvent.TournamentCompleted completed ->
                    new EncodedEvent(TOURNAMENT_COMPLETED, write(completed));
        };
    }

    public TournamentEvent decode(String eventType, String payloadJson) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payloadJson, "payloadJson");
        return switch (eventType) {
            case TOURNAMENT_STARTED -> read(payloadJson, TournamentEvent.TournamentStarted.class);
            case HAND_EVENT_RECORDED -> readRecordedHandEvent(payloadJson);
            case PLAYER_ELIMINATED -> read(payloadJson, TournamentEvent.PlayerEliminated.class);
            case BLIND_LEVEL_ADVANCED -> read(payloadJson, TournamentEvent.BlindLevelAdvanced.class);
            case TOURNAMENT_COMPLETED -> read(payloadJson, TournamentEvent.TournamentCompleted.class);
            default -> throw new IllegalArgumentException("unsupported tournament event type: " + eventType);
        };
    }

    private String writeRecordedHandEvent(TournamentEvent.HandEventRecorded recorded) {
        ObjectNode node = jsonMapper.createObjectNode();
        node.set("tournamentId", jsonMapper.valueToTree(recorded.tournamentId()));
        node.put("handEventType", handEventType(recorded.handEvent()));
        node.set("handEvent", jsonMapper.valueToTree(recorded.handEvent()));
        return write(node);
    }

    private TournamentEvent.HandEventRecorded readRecordedHandEvent(String payloadJson) {
        try {
            JsonNode node = jsonMapper.readTree(payloadJson);
            TournamentId tournamentId = jsonMapper.treeToValue(
                    node.required("tournamentId"), TournamentId.class);
            String handEventType = node.required("handEventType").asString();
            HandEvent handEvent = readHandEvent(handEventType, node.required("handEvent"));
            return new TournamentEvent.HandEventRecorded(tournamentId, handEvent);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("could not decode tournament hand event JSON", exception);
        }
    }

    private HandEvent readHandEvent(String handEventType, JsonNode payload) throws JacksonException {
        return switch (handEventType) {
            case "HAND_STARTED" -> jsonMapper.treeToValue(payload, HandEvent.HandStarted.class);
            case "HOLE_CARDS_DEALT" -> jsonMapper.treeToValue(payload, HandEvent.HoleCardsDealt.class);
            case "BLINDS_POSTED" -> jsonMapper.treeToValue(payload, HandEvent.BlindsPosted.class);
            case "PLAYER_ACTED" -> jsonMapper.treeToValue(payload, HandEvent.PlayerActed.class);
            case "COMMUNITY_CARDS_DEALT" -> jsonMapper.treeToValue(payload, HandEvent.CommunityCardsDealt.class);
            case "POTS_AWARDED" -> jsonMapper.treeToValue(payload, HandEvent.PotsAwarded.class);
            case "HAND_COMPLETED" -> jsonMapper.treeToValue(payload, HandEvent.HandCompleted.class);
            default -> throw new IllegalArgumentException("unsupported hand event type: " + handEventType);
        };
    }

    private String handEventType(HandEvent event) {
        return switch (event) {
            case HandEvent.HandStarted ignored -> "HAND_STARTED";
            case HandEvent.HoleCardsDealt ignored -> "HOLE_CARDS_DEALT";
            case HandEvent.BlindsPosted ignored -> "BLINDS_POSTED";
            case HandEvent.PlayerActed ignored -> "PLAYER_ACTED";
            case HandEvent.CommunityCardsDealt ignored -> "COMMUNITY_CARDS_DEALT";
            case HandEvent.PotsAwarded ignored -> "POTS_AWARDED";
            case HandEvent.HandCompleted ignored -> "HAND_COMPLETED";
        };
    }

    private String write(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("could not encode tournament event JSON", exception);
        }
    }

    private <T extends TournamentEvent> T read(String payloadJson, Class<T> eventType) {
        try {
            return jsonMapper.readValue(payloadJson, eventType);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("could not decode tournament event JSON", exception);
        }
    }

    public record EncodedEvent(String eventType, String payloadJson) {
        public EncodedEvent {
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(payloadJson, "payloadJson");
        }
    }
}
