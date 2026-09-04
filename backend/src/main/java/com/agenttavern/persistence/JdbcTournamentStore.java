package com.agenttavern.persistence;

import com.agenttavern.game.hand.HandId;
import com.agenttavern.tournament.Tournament;
import com.agenttavern.tournament.TournamentCheckpoint;
import com.agenttavern.tournament.TournamentEventEnvelope;
import com.agenttavern.tournament.TournamentId;
import com.agenttavern.tournament.TournamentSeat;
import com.agenttavern.tournament.port.StoredTournament;
import com.agenttavern.tournament.port.TournamentCommit;
import com.agenttavern.tournament.port.TournamentStore;
import com.agenttavern.tournament.port.TournamentWriteResult;
import com.agenttavern.tournament.port.TournamentWriteResult.WriteStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL implementation of the tournament persistence port. */
public class JdbcTournamentStore implements TournamentStore {

    private static final String LOAD_SQL = """
            select t.tournament_id, t.mode, t.status, t.version, t.last_sequence,
                   t.button_seat, t.completed_hands, t.blind_level_index,
                   s.seat_index, s.player_id, s.stack, s.status as seat_status,
                   s.finish_position, s.current_hand_starting_stack,
                   h.checkpoint_json as hand_checkpoint_json
              from tournaments t
              join tournament_seats s on s.tournament_id = t.tournament_id
              left join hand_snapshots h on h.tournament_id = t.tournament_id
             where t.tournament_id = :tournamentId
             order by s.seat_index
            """;

    private static final String FIND_RECEIPT_SQL = """
            select r.aggregate_version as receipt_version,
                   r.last_sequence as receipt_last_sequence,
                   r.event_sequence_start, r.event_sequence_end, r.event_count,
                   r.receipt_checkpoint_json,
                   e.tournament_id as event_tournament_id, e.sequence,
                   e.aggregate_version, e.occurred_at, e.occurred_epoch_second, e.occurred_nano,
                   e.event_type, e.payload_json,
                   e.hand_id
              from tournament_command_receipts r
              left join tournament_events e
                on e.tournament_id = r.tournament_id
               and r.event_count > 0
               and e.sequence between r.event_sequence_start and r.event_sequence_end
             where r.tournament_id = :tournamentId
               and r.command_id = :commandId
             order by e.sequence
            """;

    private final JdbcClient jdbcClient;
    private final TournamentCheckpointJdbcMapper checkpointMapper;
    private final TournamentEventJsonCodec eventCodec;

    public JdbcTournamentStore(
            JdbcClient jdbcClient,
            TournamentCheckpointJdbcMapper checkpointMapper,
            TournamentEventJsonCodec eventCodec) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.checkpointMapper = Objects.requireNonNull(checkpointMapper, "checkpointMapper");
        this.eventCodec = Objects.requireNonNull(eventCodec, "eventCodec");
    }

    /** Uses one joined statement so all normalized rows are read from one PostgreSQL statement snapshot. */
    @Override
    public Optional<StoredTournament> load(TournamentId tournamentId) {
        Objects.requireNonNull(tournamentId, "tournamentId");
        List<Map<String, Object>> rows = jdbcClient.sql(LOAD_SQL)
                .param("tournamentId", tournamentId.value())
                .query()
                .listOfRows();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.getFirst();
        TournamentCheckpoint checkpoint = checkpointMapper.fromNormalizedRows(rows);
        return Optional.of(new StoredTournament(
                checkpoint,
                TournamentCheckpointJdbcMapper.longValue(row.get("version")),
                TournamentCheckpointJdbcMapper.longValue(row.get("last_sequence"))));
    }

    /** Reads the checkpoint and event range recorded for the original command, never the current snapshot. */
    @Override
    public Optional<TournamentWriteResult> findCommand(TournamentId tournamentId, UUID commandId) {
        Objects.requireNonNull(tournamentId, "tournamentId");
        Objects.requireNonNull(commandId, "commandId");
        List<Map<String, Object>> rows = jdbcClient.sql(FIND_RECEIPT_SQL)
                .param("tournamentId", tournamentId.value())
                .param("commandId", commandId)
                .query()
                .listOfRows();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> receipt = rows.getFirst();
        TournamentCheckpoint checkpoint = checkpointMapper.checkpointFromJson(
                TournamentCheckpointJdbcMapper.jsonValue(receipt.get("receipt_checkpoint_json")));
        StoredTournament storedTournament = new StoredTournament(
                checkpoint,
                TournamentCheckpointJdbcMapper.longValue(receipt.get("receipt_version")),
                TournamentCheckpointJdbcMapper.longValue(receipt.get("receipt_last_sequence")));
        int expectedEventCount = TournamentCheckpointJdbcMapper.intValue(receipt.get("event_count"));
        List<TournamentEventEnvelope> events = rows.stream()
                .filter(row -> row.get("event_tournament_id") != null)
                .map(this::event)
                .toList();
        validateReceiptRange(receipt, events, expectedEventCount);
        return Optional.of(new TournamentWriteResult(WriteStatus.ALREADY_APPLIED, storedTournament, events));
    }

    @Override
    @Transactional
    public TournamentWriteResult commit(TournamentCommit commit) {
        Objects.requireNonNull(commit, "commit");
        TournamentId tournamentId = commit.checkpoint().id();
        Optional<TournamentWriteResult> receipt = findCommand(tournamentId, commit.commandId());
        if (receipt.isPresent()) {
            return receipt.get();
        }

        Optional<StoredTournament> current = load(tournamentId);
        if (!matchesExpectedVersion(commit.expectedVersion(), current.orElse(null))) {
            return duplicateOrConflict(tournamentId, commit.commandId());
        }
        CommitState state = validate(commit, current.orElse(null));
        int changed = current.isEmpty()
                ? insertTournament(commit.checkpoint(), state)
                : updateTournament(commit.checkpoint(), state);
        if (changed != 1) {
            return duplicateOrConflict(tournamentId, commit.commandId());
        }

        replaceSeats(commit.checkpoint());
        replaceHandSnapshot(commit.checkpoint());
        insertEvents(commit.events());
        int receiptInserted = insertReceipt(commit, state);
        if (receiptInserted != 1) {
            return duplicateOrConflict(tournamentId, commit.commandId());
        }

        return new TournamentWriteResult(
                WriteStatus.APPLIED,
                new StoredTournament(commit.checkpoint(), state.nextVersion(), state.nextLastSequence()),
                commit.events());
    }

    @Override
    public List<TournamentEventEnvelope> eventsAfter(TournamentId tournamentId, long sequenceExclusive) {
        Objects.requireNonNull(tournamentId, "tournamentId");
        if (sequenceExclusive < 0) {
            throw new IllegalArgumentException("sequence exclusive must be non-negative");
        }
        return jdbcClient.sql("""
                        select tournament_id as event_tournament_id, sequence, aggregate_version,
                               occurred_at, occurred_epoch_second, occurred_nano,
                               event_type, payload_json, hand_id
                          from tournament_events
                         where tournament_id = :tournamentId
                           and sequence > :sequenceExclusive
                         order by sequence
                        """)
                .param("tournamentId", tournamentId.value())
                .param("sequenceExclusive", sequenceExclusive)
                .query()
                .listOfRows()
                .stream()
                .map(this::event)
                .toList();
    }

    private int insertTournament(TournamentCheckpoint checkpoint, CommitState state) {
        return jdbcClient.sql("""
                        insert into tournaments (
                            tournament_id, mode, status, version, last_sequence, button_seat,
                            completed_hands, blind_level_index, updated_at
                        ) values (
                            :tournamentId, :mode, :status, :version, :lastSequence, :buttonSeat,
                            :completedHands, :blindLevelIndex, current_timestamp
                        ) on conflict (tournament_id) do nothing
                        """)
                .param("tournamentId", checkpoint.id().value())
                .param("mode", checkpoint.mode().name())
                .param("status", checkpoint.status().name())
                .param("version", state.nextVersion())
                .param("lastSequence", state.nextLastSequence())
                .param("buttonSeat", checkpoint.buttonSeat())
                .param("completedHands", checkpoint.completedHands())
                .param("blindLevelIndex", checkpoint.blindLevelIndex())
                .update();
    }

    private int updateTournament(TournamentCheckpoint checkpoint, CommitState state) {
        return jdbcClient.sql("""
                        update tournaments
                           set mode = :mode,
                               status = :status,
                               version = :nextVersion,
                               last_sequence = :nextLastSequence,
                               button_seat = :buttonSeat,
                               completed_hands = :completedHands,
                               blind_level_index = :blindLevelIndex,
                               updated_at = current_timestamp
                         where tournament_id = :tournamentId
                           and version = :expectedVersion
                        """)
                .param("mode", checkpoint.mode().name())
                .param("status", checkpoint.status().name())
                .param("nextVersion", state.nextVersion())
                .param("nextLastSequence", state.nextLastSequence())
                .param("buttonSeat", checkpoint.buttonSeat())
                .param("completedHands", checkpoint.completedHands())
                .param("blindLevelIndex", checkpoint.blindLevelIndex())
                .param("tournamentId", checkpoint.id().value())
                .param("expectedVersion", state.expectedVersion())
                .update();
    }

    private void replaceSeats(TournamentCheckpoint checkpoint) {
        for (TournamentSeat seat : checkpoint.seats()) {
            int updated = jdbcClient.sql("""
                            insert into tournament_seats (
                                tournament_id, seat_index, player_id, stack, status,
                                finish_position, current_hand_starting_stack
                            ) values (
                                :tournamentId, :seatIndex, :playerId, :stack, :status,
                                :finishPosition, :currentHandStartingStack
                            ) on conflict (tournament_id, seat_index) do update
                              set player_id = excluded.player_id,
                                  stack = excluded.stack,
                                  status = excluded.status,
                                  finish_position = excluded.finish_position,
                                  current_hand_starting_stack = excluded.current_hand_starting_stack
                            """)
                    .param("tournamentId", checkpoint.id().value())
                    .param("seatIndex", seat.seatIndex())
                    .param("playerId", seat.playerId().value())
                    .param("stack", seat.stack())
                    .param("status", seat.status().name())
                    .param("finishPosition", seat.finishPosition())
                    .param("currentHandStartingStack", checkpoint.currentHandStartingStacks()
                            .get(seat.playerId()))
                    .update();
            requireCount(updated, 1, "seat upsert");
        }
        Map<String, Object> deletionParameters = new HashMap<>();
        deletionParameters.put("tournamentId", checkpoint.id().value());
        StringBuilder staleSeatSql = new StringBuilder(
                "delete from tournament_seats where tournament_id = :tournamentId and seat_index not in (");
        for (int index = 0; index < checkpoint.seats().size(); index++) {
            if (index > 0) {
                staleSeatSql.append(", ");
            }
            String parameter = "seatIndex" + index;
            staleSeatSql.append(':').append(parameter);
            deletionParameters.put(parameter, checkpoint.seats().get(index).seatIndex());
        }
        staleSeatSql.append(')');
        jdbcClient.sql(staleSeatSql.toString()).params(deletionParameters).update();
    }

    private void replaceHandSnapshot(TournamentCheckpoint checkpoint) {
        if (checkpoint.currentHand().isEmpty()) {
            jdbcClient.sql("delete from hand_snapshots where tournament_id = :tournamentId")
                    .param("tournamentId", checkpoint.id().value())
                    .update();
            return;
        }
        var hand = checkpoint.currentHand().orElseThrow();
        int updated = jdbcClient.sql("""
                        insert into hand_snapshots (tournament_id, hand_id, checkpoint_json)
                        values (:tournamentId, :handId, cast(:checkpointJson as jsonb))
                        on conflict (tournament_id) do update
                          set hand_id = excluded.hand_id,
                              checkpoint_json = excluded.checkpoint_json
                        """)
                .param("tournamentId", checkpoint.id().value())
                .param("handId", hand.id().value())
                .param("checkpointJson", checkpointMapper.handCheckpointJson(hand))
                .update();
        requireCount(updated, 1, "hand snapshot replacement");
    }

    private void insertEvents(List<TournamentEventEnvelope> events) {
        if (events.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder("""
                insert into tournament_events (
                    tournament_event_id, tournament_id, sequence, aggregate_version,
                    occurred_at, occurred_epoch_second, occurred_nano,
                    event_type, payload_json, hand_id
                ) values """);
        Map<String, Object> parameters = new HashMap<>();
        for (int index = 0; index < events.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            TournamentEventEnvelope event = events.get(index);
            TournamentEventJsonCodec.EncodedEvent encoded = eventCodec.encode(event.payload());
            sql.append("(:eventId").append(index)
                    .append(", :tournamentId").append(index)
                    .append(", :sequence").append(index)
                    .append(", :aggregateVersion").append(index)
                    .append(", :occurredAt").append(index)
                    .append(", :occurredEpochSecond").append(index)
                    .append(", :occurredNano").append(index)
                    .append(", :eventType").append(index)
                    .append(", cast(:payloadJson").append(index).append(" as jsonb)")
                    .append(", :handId").append(index).append(')');
            parameters.put("eventId" + index, UUID.randomUUID());
            parameters.put("tournamentId" + index, event.tournamentId().value());
            parameters.put("sequence" + index, event.sequence());
            parameters.put("aggregateVersion" + index, event.aggregateVersion());
            // PostgreSQL JDBC does not infer a SQL type for Instant. OffsetDateTime binds as timestamptz;
            // the adjacent epoch-second/nano columns retain the event's full nanosecond precision.
            parameters.put("occurredAt" + index, OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC));
            parameters.put("occurredEpochSecond" + index, event.occurredAt().getEpochSecond());
            parameters.put("occurredNano" + index, event.occurredAt().getNano());
            parameters.put("eventType" + index, encoded.eventType());
            parameters.put("payloadJson" + index, encoded.payloadJson());
            parameters.put("handId" + index, event.handId().map(HandId::value).orElse(null));
        }
        int inserted = jdbcClient.sql(sql.toString()).params(parameters).update();
        requireCount(inserted, events.size(), "event batch insert");
    }

    private int insertReceipt(TournamentCommit commit, CommitState state) {
        long firstSequence = commit.events().isEmpty()
                ? state.nextLastSequence()
                : commit.events().getFirst().sequence();
        return jdbcClient.sql("""
                        insert into tournament_command_receipts (
                            receipt_id, tournament_id, command_id, aggregate_version, last_sequence,
                            event_sequence_start, event_sequence_end, event_count,
                            receipt_checkpoint_json, created_at
                        ) values (
                            :receiptId, :tournamentId, :commandId, :aggregateVersion, :lastSequence,
                            :eventSequenceStart, :eventSequenceEnd, :eventCount,
                            cast(:checkpointJson as jsonb), current_timestamp
                        ) on conflict (tournament_id, command_id) do nothing
                        """)
                .param("receiptId", UUID.randomUUID())
                .param("tournamentId", commit.checkpoint().id().value())
                .param("commandId", commit.commandId())
                .param("aggregateVersion", state.nextVersion())
                .param("lastSequence", state.nextLastSequence())
                .param("eventSequenceStart", firstSequence)
                .param("eventSequenceEnd", state.nextLastSequence())
                .param("eventCount", commit.events().size())
                .param("checkpointJson", checkpointMapper.checkpointJson(commit.checkpoint()))
                .update();
    }

    private TournamentWriteResult duplicateOrConflict(TournamentId tournamentId, UUID commandId) {
        return findCommand(tournamentId, commandId)
                .orElseThrow(() -> new ConcurrentModificationException("tournament version has changed"));
    }

    private CommitState validate(TournamentCommit commit, StoredTournament current) {
        TournamentCheckpoint checkpoint = commit.checkpoint();
        Tournament.restore(checkpoint);
        long nextVersion = Math.incrementExact(commit.expectedVersion());
        long sequence = current == null ? 0 : current.lastSequence();
        for (TournamentEventEnvelope event : commit.events()) {
            if (!checkpoint.id().equals(event.tournamentId())
                    || !checkpoint.id().equals(event.payload().tournamentId())) {
                throw new IllegalArgumentException("event tournament ID must match checkpoint ID");
            }
            sequence = Math.incrementExact(sequence);
            if (event.sequence() != sequence) {
                throw new IllegalArgumentException("event sequences must be continuous");
            }
            if (event.aggregateVersion() != nextVersion) {
                throw new IllegalArgumentException(
                        "event aggregate version must match the committed version");
            }
        }
        return new CommitState(commit.expectedVersion(), nextVersion, sequence);
    }

    private boolean matchesExpectedVersion(long expectedVersion, StoredTournament current) {
        return current == null ? expectedVersion == 0 : current.version() == expectedVersion;
    }

    private TournamentEventEnvelope event(Map<String, Object> row) {
        UUID handId = row.get("hand_id") == null ? null : TournamentCheckpointJdbcMapper.uuid(row.get("hand_id"));
        return new TournamentEventEnvelope(
                new TournamentId(TournamentCheckpointJdbcMapper.uuid(row.get("event_tournament_id"))),
                Optional.ofNullable(handId).map(HandId::new),
                TournamentCheckpointJdbcMapper.longValue(row.get("sequence")),
                TournamentCheckpointJdbcMapper.longValue(row.get("aggregate_version")),
                exactOccurredAt(row.get("occurred_epoch_second"), row.get("occurred_nano")),
                eventCodec.decode(
                        String.valueOf(row.get("event_type")),
                        TournamentCheckpointJdbcMapper.jsonValue(row.get("payload_json"))));
    }

    private void validateReceiptRange(
            Map<String, Object> receipt, List<TournamentEventEnvelope> events, int expectedCount) {
        if (events.size() != expectedCount) {
            throw new IllegalStateException("command receipt event range is incomplete");
        }
        long start = TournamentCheckpointJdbcMapper.longValue(receipt.get("event_sequence_start"));
        long end = TournamentCheckpointJdbcMapper.longValue(receipt.get("event_sequence_end"));
        if (events.isEmpty()) {
            if (start != end) {
                throw new IllegalStateException("empty command receipt has an invalid event range");
            }
            return;
        }
        if (events.getFirst().sequence() != start || events.getLast().sequence() != end) {
            throw new IllegalStateException("command receipt event bounds do not match its events");
        }
    }

    static Instant exactOccurredAt(Object epochSecond, Object nano) {
        return Instant.ofEpochSecond(
                TournamentCheckpointJdbcMapper.longValue(epochSecond),
                TournamentCheckpointJdbcMapper.intValue(nano));
    }

    private static void requireCount(int actual, int expected, String operation) {
        if (actual != expected) {
            throw new IllegalStateException(operation + " affected " + actual + " rows, expected " + expected);
        }
    }

    private record CommitState(long expectedVersion, long nextVersion, long nextLastSequence) {}
}
