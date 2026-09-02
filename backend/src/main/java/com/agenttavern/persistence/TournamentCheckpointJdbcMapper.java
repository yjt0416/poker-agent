package com.agenttavern.persistence;

import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.hand.HandCheckpoint;
import com.agenttavern.game.hand.HandId;
import com.agenttavern.tournament.TournamentCheckpoint;
import com.agenttavern.tournament.TournamentId;
import com.agenttavern.tournament.TournamentMode;
import com.agenttavern.tournament.TournamentSeat;
import com.agenttavern.tournament.TournamentSeatStatus;
import com.agenttavern.tournament.TournamentStatus;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.KeyDeserializer;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/** Maps normalized JDBC rows and the private checkpoint JSON used by hand snapshots and receipts. */
public final class TournamentCheckpointJdbcMapper {

    private final JsonMapper jsonMapper;

    public TournamentCheckpointJdbcMapper(JsonMapper jsonMapper) {
        this.jsonMapper = java.util.Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    /** Shared, explicitly configured Jackson 3 mapper. It deliberately has no default typing. */
    public static JsonMapper jsonMapper() {
        SimpleModule playerIdMapKeys = new SimpleModule("player-id-map-keys");
        playerIdMapKeys.addKeySerializer(PlayerId.class, new PlayerIdKeySerializer());
        playerIdMapKeys.addKeyDeserializer(PlayerId.class, new PlayerIdKeyDeserializer());
        return JsonMapper.builder()
                .addModule(playerIdMapKeys)
                .addMixIn(TournamentId.class, TournamentIdMixin.class)
                .addMixIn(HandId.class, HandIdMixin.class)
                .addMixIn(PlayerId.class, PlayerIdMixin.class)
                .build();
    }

    public String checkpointJson(TournamentCheckpoint checkpoint) {
        return write(checkpoint);
    }

    public TournamentCheckpoint checkpointFromJson(String checkpointJson) {
        return read(checkpointJson, TournamentCheckpoint.class);
    }

    public String handCheckpointJson(HandCheckpoint checkpoint) {
        return write(checkpoint);
    }

    public HandCheckpoint handCheckpointFromJson(String checkpointJson) {
        return read(checkpointJson, HandCheckpoint.class);
    }

    /** Rebuilds the public normalized snapshot, retaining private hand state only from its JSON row. */
    public TournamentCheckpoint fromNormalizedRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("normalized tournament rows must not be empty");
        }
        Map<String, Object> tournament = rows.getFirst();
        List<TournamentSeat> seats = rows.stream()
                .map(this::seat)
                .sorted(Comparator.comparingInt(TournamentSeat::seatIndex))
                .toList();
        Map<PlayerId, Long> startingStacks = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object startingStack = row.get("current_hand_starting_stack");
            if (startingStack != null) {
                startingStacks.put(playerId(row.get("player_id")), longValue(startingStack));
            }
        }
        Object handJson = tournament.get("hand_checkpoint_json");
        Optional<HandCheckpoint> currentHand = handJson == null
                ? Optional.empty()
                : Optional.of(handCheckpointFromJson(jsonValue(handJson)));
        return new TournamentCheckpoint(
                new TournamentId(uuid(tournament.get("tournament_id"))),
                enumValue(TournamentMode.class, tournament.get("mode")),
                enumValue(TournamentStatus.class, tournament.get("status")),
                seats,
                intValue(tournament.get("button_seat")),
                intValue(tournament.get("completed_hands")),
                intValue(tournament.get("blind_level_index")),
                currentHand,
                startingStacks);
    }

    public TournamentSeat seat(Map<String, Object> row) {
        Integer finishPosition = row.get("finish_position") == null
                ? null
                : intValue(row.get("finish_position"));
        return new TournamentSeat(
                playerId(row.get("player_id")),
                intValue(row.get("seat_index")),
                longValue(row.get("stack")),
                enumValue(TournamentSeatStatus.class, row.get("seat_status")),
                finishPosition);
    }

    public static UUID uuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(String.valueOf(value));
    }

    public static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    public static int intValue(Object value) {
        try {
            return Math.toIntExact(longValue(value));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("database integer is outside the Java int range", exception);
        }
    }

    public static String jsonValue(Object value) {
        return String.valueOf(value);
    }

    private PlayerId playerId(Object value) {
        return new PlayerId(uuid(value));
    }

    private <T extends Enum<T>> T enumValue(Class<T> enumType, Object value) {
        return Enum.valueOf(enumType, String.valueOf(value));
    }

    private String write(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("could not encode private tournament checkpoint JSON", exception);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return jsonMapper.readValue(json, type);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("could not decode private tournament checkpoint JSON", exception);
        }
    }

    private abstract static class TournamentIdMixin {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        TournamentIdMixin(UUID value) {}

        @JsonValue
        abstract UUID value();
    }

    private abstract static class HandIdMixin {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        HandIdMixin(UUID value) {}

        @JsonValue
        abstract UUID value();
    }

    private abstract static class PlayerIdMixin {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        PlayerIdMixin(UUID value) {}

        @JsonValue
        abstract UUID value();
    }

    private static final class PlayerIdKeySerializer extends ValueSerializer<PlayerId> {
        @Override
        public void serialize(PlayerId value, JsonGenerator generator, SerializationContext context)
                throws JacksonException {
            generator.writeName(value.value().toString());
        }
    }

    private static final class PlayerIdKeyDeserializer extends KeyDeserializer {
        @Override
        public Object deserializeKey(String key, DeserializationContext context) {
            return new PlayerId(UUID.fromString(key));
        }
    }
}
