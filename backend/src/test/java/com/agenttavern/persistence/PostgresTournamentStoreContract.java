package com.agenttavern.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenttavern.game.betting.PlayerAction;
import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.card.Deck;
import com.agenttavern.game.hand.HandId;
import com.agenttavern.tournament.Tournament;
import com.agenttavern.tournament.TournamentEntrant;
import com.agenttavern.tournament.TournamentEvent;
import com.agenttavern.tournament.TournamentEventEnvelope;
import com.agenttavern.tournament.TournamentId;
import com.agenttavern.tournament.TournamentMode;
import com.agenttavern.tournament.TournamentTransition;
import com.agenttavern.tournament.application.ActInTournamentCommand;
import com.agenttavern.tournament.application.CreateTournamentCommand;
import com.agenttavern.tournament.application.TournamentCommandService;
import com.agenttavern.tournament.application.TournamentExecution;
import com.agenttavern.tournament.port.StoredTournament;
import com.agenttavern.tournament.port.TournamentCommit;
import com.agenttavern.tournament.port.TournamentStore;
import com.agenttavern.tournament.port.TournamentWriteResult;
import com.agenttavern.tournament.port.TournamentWriteResult.WriteStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * Behavioral acceptance contract for both Docker and deliberately opted-in local PostgreSQL.
 * The store is always the Spring-created transactional proxy; only the database endpoint differs.
 */
abstract class PostgresTournamentStoreContract {

    private static final Instant NANO_INSTANT = Instant.parse("2026-09-04T07:50:01.123456789Z");

    private final List<TournamentId> ownedTournaments = new ArrayList<>();
    private final List<String> ownedConstraints = new ArrayList<>();

    @Autowired
    private JdbcTournamentStore jdbcTournamentStore;

    @Autowired
    private TournamentStore tournamentStore;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void usesTheProductionSpringStoreAndTransactionProxy() {
        assertThat(tournamentStore).isSameAs(jdbcTournamentStore);
        assertThat(AopUtils.isCglibProxy(jdbcTournamentStore)).isTrue();
        assertThat(((Advised) jdbcTournamentStore).getAdvisors())
                .anySatisfy(advisor -> assertThat(advisor.getAdvice())
                        .isInstanceOf(TransactionInterceptor.class));
    }

    @AfterEach
    void removeOnlyRowsAndConstraintsOwnedByThisTest() {
        for (String constraint : ownedConstraints) {
            jdbcClient.sql("alter table tournament_events drop constraint if exists " + constraint).update();
        }
        for (TournamentId tournamentId : ownedTournaments) {
            jdbcClient.sql("delete from tournaments where tournament_id = :tournamentId")
                    .param("tournamentId", tournamentId.value())
                    .update();
        }
    }

    @Test
    void flywayMigratesAndRestartRecoversExactNextTransition() {
        assertThat(jdbcClient.sql("select success from flyway_schema_history where version = '1'")
                        .query(Boolean.class)
                        .single())
                .isTrue();

        TournamentId tournamentId = newTournamentId();
        UUID createCommandId = UUID.randomUUID();
        TournamentExecution created = commandService().create(createCommand(createCommandId, tournamentId));
        StoredTournament durableBeforeRestart = tournamentStore.load(tournamentId).orElseThrow();
        assertThat(durableBeforeRestart.checkpoint()).isEqualTo(created.checkpoint());

        UUID nextCommandId = UUID.randomUUID();
        TournamentCommit expectedCommit = actionCommit(durableBeforeRestart, nextCommandId);
        TournamentCommandService restarted = commandService();
        assertThat(restarted).isNotSameAs(commandService());

        TournamentExecution recovered = restarted.act(new ActInTournamentCommand(
                nextCommandId,
                tournamentId,
                durableBeforeRestart.version(),
                currentActor(durableBeforeRestart.checkpoint()),
                PlayerAction.call()));

        assertThat(recovered.checkpoint()).isEqualTo(expectedCommit.checkpoint());
        assertThat(recovered.version()).isEqualTo(durableBeforeRestart.version() + 1);
        assertThat(recovered.lastSequence()).isEqualTo(
                durableBeforeRestart.lastSequence() + expectedCommit.events().size());
        assertThat(recovered.events()).isEqualTo(expectedCommit.events());
        assertThat(tournamentStore.load(tournamentId).orElseThrow().checkpoint())
                .isEqualTo(expectedCommit.checkpoint());
    }

    @Test
    void preservesNanosecondEventTimesInTheStreamAndOriginalReceipt() {
        TournamentId tournamentId = newTournamentId();
        UUID commandId = UUID.randomUUID();
        TournamentExecution created = commandService().create(createCommand(commandId, tournamentId));

        assertThat(created.events()).extracting(TournamentEventEnvelope::occurredAt)
                .containsOnly(NANO_INSTANT);
        assertThat(tournamentStore.eventsAfter(tournamentId, 0))
                .extracting(TournamentEventEnvelope::occurredAt)
                .containsOnly(NANO_INSTANT);
        assertThat(tournamentStore.findCommand(tournamentId, commandId).orElseThrow().events())
                .extracting(TournamentEventEnvelope::occurredAt)
                .containsOnly(NANO_INSTANT);
    }

    @Test
    void originalReceiptSurvivesLaterCommandsWithoutReturningTheNewSnapshot() {
        TournamentId tournamentId = newTournamentId();
        UUID createCommandId = UUID.randomUUID();
        CreateTournamentCommand create = createCommand(createCommandId, tournamentId);
        TournamentCommandService service = commandService();
        TournamentExecution original = service.create(create);
        TournamentExecution later = service.act(new ActInTournamentCommand(
                UUID.randomUUID(),
                tournamentId,
                original.version(),
                currentActor(original.checkpoint()),
                PlayerAction.call()));

        TournamentExecution receipt = service.create(create);

        assertThat(receipt).isEqualTo(original);
        assertThat(receipt.checkpoint()).isNotEqualTo(later.checkpoint());
        assertThat(tournamentStore.load(tournamentId).orElseThrow().checkpoint())
                .isEqualTo(later.checkpoint());
    }

    @Test
    void eventsAfterIsStrictlyOrderedAndGapFree() {
        TournamentId tournamentId = newTournamentId();
        TournamentExecution created = commandService().create(createCommand(UUID.randomUUID(), tournamentId));
        commandService().act(new ActInTournamentCommand(
                UUID.randomUUID(),
                tournamentId,
                created.version(),
                currentActor(created.checkpoint()),
                PlayerAction.call()));

        assertThat(tournamentStore.eventsAfter(tournamentId, 0))
                .extracting(TournamentEventEnvelope::sequence)
                .containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(tournamentStore.eventsAfter(tournamentId, 2))
                .extracting(TournamentEventEnvelope::sequence)
                .containsExactly(3L, 4L, 5L);
    }

    @Test
    void databaseFailureAfterSnapshotSeatAndHandWritesRollsBackEveryBusinessTable() {
        TournamentId tournamentId = newTournamentId();
        UUID originalCommandId = UUID.randomUUID();
        TournamentExecution created = commandService().create(createCommand(originalCommandId, tournamentId));
        StoredTournament beforeTransition = tournamentStore.load(tournamentId).orElseThrow();
        TournamentCommit rejectedCommit = actionCommit(beforeTransition, UUID.randomUUID());
        DatabaseState before = databaseState(tournamentId, originalCommandId);
        String constraint = rejectEventFor(tournamentId, rejectedCommit.events().getFirst().sequence());

        assertThatThrownBy(() -> tournamentStore.commit(rejectedCommit))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(databaseState(tournamentId, originalCommandId)).isEqualTo(before);
        assertThat(ownedConstraints).contains(constraint);
    }

    @Test
    void staleStoreCommitLeavesEverySavedRepresentationAtTheCompetingVersion() {
        TournamentId tournamentId = newTournamentId();
        UUID originalCommandId = UUID.randomUUID();
        commandService().create(createCommand(originalCommandId, tournamentId));
        StoredTournament original = tournamentStore.load(tournamentId).orElseThrow();
        TournamentCommit winner = actionCommit(original, UUID.randomUUID());
        TournamentCommit stale = actionCommit(original, UUID.randomUUID());

        TournamentWriteResult applied = tournamentStore.commit(winner);
        DatabaseState afterWinner = databaseState(tournamentId, originalCommandId);

        assertThat(applied.status()).isEqualTo(WriteStatus.APPLIED);
        assertThatThrownBy(() -> tournamentStore.commit(stale))
                .isInstanceOf(ConcurrentModificationException.class);
        assertThat(databaseState(tournamentId, originalCommandId)).isEqualTo(afterWinner);
        assertThat(tournamentStore.load(tournamentId).orElseThrow().version()).isEqualTo(2L);
    }

    @Test
    void independentCallersWithTheSameCommandCreateExactlyOneReceiptAndOneEventBatch() throws Exception {
        TournamentId tournamentId = newTournamentId();
        UUID originalCommandId = UUID.randomUUID();
        TournamentExecution created = commandService().create(createCommand(originalCommandId, tournamentId));
        UUID commandId = UUID.randomUUID();
        CoordinatedTournamentStore callerOneStore = coordinatedStore();
        CoordinatedTournamentStore callerTwoStore = callerOneStore.peer();
        TournamentCommandService callerOne = commandService(callerOneStore);
        TournamentCommandService callerTwo = commandService(callerTwoStore);

        List<TournamentExecution> results = concurrently(
                () -> callerOne.act(actionCommand(commandId, created)),
                () -> callerTwo.act(actionCommand(commandId, created)));

        assertThat(callerOne).isNotSameAs(callerTwo);
        assertThat(results.getFirst()).isEqualTo(results.get(1));
        assertThat(callerOneStore.commitCalls()).isEqualTo(2);
        assertThat(tournamentStore.eventsAfter(tournamentId, 0))
                .extracting(TournamentEventEnvelope::sequence)
                .containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(rows("tournament_command_receipts", tournamentId, "command_id")).hasSize(2);
    }

    @Test
    void independentCallersWithDistinctCommandsRaceAtTheDatabaseAndOnlyOneWins() throws Exception {
        TournamentId tournamentId = newTournamentId();
        TournamentExecution created = commandService().create(createCommand(UUID.randomUUID(), tournamentId));
        CoordinatedTournamentStore callerOneStore = coordinatedStore();
        CoordinatedTournamentStore callerTwoStore = callerOneStore.peer();
        TournamentCommandService callerOne = commandService(callerOneStore);
        TournamentCommandService callerTwo = commandService(callerTwoStore);

        List<Outcome<TournamentExecution>> outcomes = concurrentlyOutcomes(
                () -> callerOne.act(actionCommand(UUID.randomUUID(), created)),
                () -> callerTwo.act(actionCommand(UUID.randomUUID(), created)));

        assertThat(callerOne).isNotSameAs(callerTwo);
        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> !outcome.succeeded())
                .allSatisfy(outcome -> assertThat(outcome.failure())
                        .isInstanceOf(com.agenttavern.tournament.application.ConcurrentTournamentUpdateException.class));
        assertThat(callerOneStore.commitCalls()).isEqualTo(2);
        assertThat(tournamentStore.load(tournamentId).orElseThrow().version()).isEqualTo(2L);
        assertThat(tournamentStore.eventsAfter(tournamentId, 0))
                .extracting(TournamentEventEnvelope::sequence)
                .containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    private TournamentCommandService commandService() {
        return commandService(tournamentStore);
    }

    private TournamentCommandService commandService(TournamentStore store) {
        return new TournamentCommandService(store, Clock.fixed(NANO_INSTANT, ZoneOffset.UTC), Deck::standard);
    }

    private TournamentId newTournamentId() {
        TournamentId tournamentId = new TournamentId(UUID.randomUUID());
        ownedTournaments.add(tournamentId);
        return tournamentId;
    }

    private static CreateTournamentCommand createCommand(UUID commandId, TournamentId tournamentId) {
        return new CreateTournamentCommand(
                commandId,
                tournamentId,
                TournamentMode.PLAYER,
                List.of(
                        new TournamentEntrant(player(1), 0),
                        new TournamentEntrant(player(2), 1),
                        new TournamentEntrant(player(3), 2),
                        new TournamentEntrant(player(4), 3),
                        new TournamentEntrant(player(5), 4),
                        new TournamentEntrant(player(6), 5)),
                0,
                hand(1));
    }

    private static ActInTournamentCommand actionCommand(UUID commandId, TournamentExecution execution) {
        return new ActInTournamentCommand(
                commandId,
                execution.checkpoint().id(),
                execution.version(),
                currentActor(execution.checkpoint()),
                PlayerAction.call());
    }

    private static TournamentCommit actionCommit(StoredTournament stored, UUID commandId) {
        TournamentTransition transition = Tournament.restore(stored.checkpoint())
                .act(currentActor(stored.checkpoint()), PlayerAction.call());
        long aggregateVersion = Math.incrementExact(stored.version());
        long sequence = stored.lastSequence();
        List<TournamentEventEnvelope> events = new ArrayList<>();
        for (TournamentEvent event : transition.events()) {
            sequence = Math.incrementExact(sequence);
            Optional<HandId> handId = event instanceof TournamentEvent.HandEventRecorded recorded
                    ? Optional.of(recorded.handEvent().handId())
                    : Optional.empty();
            events.add(new TournamentEventEnvelope(
                    stored.checkpoint().id(), handId, sequence, aggregateVersion, NANO_INSTANT, event));
        }
        return new TournamentCommit(commandId, stored.version(), transition.tournament().checkpoint(), events);
    }

    private static PlayerId currentActor(com.agenttavern.tournament.TournamentCheckpoint checkpoint) {
        return Tournament.restore(checkpoint).currentHand().actor().playerId();
    }

    private static PlayerId player(long value) {
        return new PlayerId(new UUID(0, value));
    }

    private static HandId hand(long value) {
        return new HandId(new UUID(1, value));
    }

    private String rejectEventFor(TournamentId tournamentId, long sequence) {
        String constraint = "postgres_it_event_" + UUID.randomUUID().toString().replace("-", "");
        jdbcClient.sql("""
                        alter table tournament_events add constraint %s
                        check (tournament_id <> '%s'::uuid or sequence <> %d) not valid
                        """.formatted(constraint, tournamentId.value(), sequence))
                .update();
        ownedConstraints.add(constraint);
        return constraint;
    }

    private CoordinatedTournamentStore coordinatedStore() {
        return new CoordinatedTournamentStore(tournamentStore, new CyclicBarrier(2));
    }

    private DatabaseState databaseState(TournamentId tournamentId, UUID originalCommandId) {
        return new DatabaseState(
                tournamentStore.load(tournamentId),
                tournamentStore.eventsAfter(tournamentId, 0),
                tournamentStore.findCommand(tournamentId, originalCommandId),
                Map.of(
                        "tournaments", rows("tournaments", tournamentId, "tournament_id"),
                        "tournament_seats", rows("tournament_seats", tournamentId, "seat_index"),
                        "hand_snapshots", rows("hand_snapshots", tournamentId, "hand_id"),
                        "tournament_events", rows("tournament_events", tournamentId, "sequence"),
                        "tournament_command_receipts", rows("tournament_command_receipts", tournamentId, "command_id")));
    }

    private List<Map<String, Object>> rows(String table, TournamentId tournamentId, String orderBy) {
        return jdbcClient.sql("select * from " + table
                        + " where tournament_id = :tournamentId order by " + orderBy)
                .param("tournamentId", tournamentId.value())
                .query()
                .listOfRows()
                .stream()
                // JDBC result rows legitimately contain null hand IDs and finish positions.
                .<Map<String, Object>>map(LinkedHashMap::new)
                .toList();
    }

    private static <T> List<T> concurrently(Supplier<T> first, Supplier<T> second) throws Exception {
        CyclicBarrier startingGate = new CyclicBarrier(3);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<T> firstResult = executor.submit(() -> {
                await(startingGate);
                return first.get();
            });
            Future<T> secondResult = executor.submit(() -> {
                await(startingGate);
                return second.get();
            });
            await(startingGate);
            return List.of(
                    firstResult.get(10, TimeUnit.SECONDS), secondResult.get(10, TimeUnit.SECONDS));
        }
    }

    private static <T> List<Outcome<T>> concurrentlyOutcomes(Supplier<T> first, Supplier<T> second)
            throws Exception {
        CyclicBarrier startingGate = new CyclicBarrier(3);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Outcome<T>> firstResult = executor.submit(() -> runAfterGate(startingGate, first));
            Future<Outcome<T>> secondResult = executor.submit(() -> runAfterGate(startingGate, second));
            await(startingGate);
            return List.of(
                    firstResult.get(10, TimeUnit.SECONDS), secondResult.get(10, TimeUnit.SECONDS));
        }
    }

    private static <T> Outcome<T> runAfterGate(CyclicBarrier gate, Supplier<T> action) {
        await(gate);
        try {
            return Outcome.success(action.get());
        } catch (RuntimeException exception) {
            return Outcome.failure(exception);
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting PostgreSQL race", exception);
        } catch (BrokenBarrierException | java.util.concurrent.TimeoutException exception) {
            throw new AssertionError("PostgreSQL race barrier did not complete", exception);
        }
    }

    private record DatabaseState(
            Optional<StoredTournament> stored,
            List<TournamentEventEnvelope> events,
            Optional<TournamentWriteResult> originalReceipt,
            Map<String, List<Map<String, Object>>> tableRows) {}

    private record Outcome<T>(T value, RuntimeException failure) {
        static <T> Outcome<T> success(T value) {
            return new Outcome<>(value, null);
        }

        static <T> Outcome<T> failure(RuntimeException failure) {
            return new Outcome<>(null, failure);
        }

        boolean succeeded() {
            return failure == null;
        }
    }

    /**
     * A test-only gate after each independent service has loaded the same snapshot. It delegates all
     * writes to the production CGLIB transaction proxy, proving that both callers reach PostgreSQL.
     */
    private static final class CoordinatedTournamentStore implements TournamentStore {
        private final TournamentStore delegate;
        private final CyclicBarrier loadedSnapshotGate;
        private final AtomicInteger commitCalls;

        private CoordinatedTournamentStore(TournamentStore delegate, CyclicBarrier loadedSnapshotGate) {
            this(delegate, loadedSnapshotGate, new AtomicInteger());
        }

        private CoordinatedTournamentStore(
                TournamentStore delegate, CyclicBarrier loadedSnapshotGate, AtomicInteger commitCalls) {
            this.delegate = delegate;
            this.loadedSnapshotGate = loadedSnapshotGate;
            this.commitCalls = commitCalls;
        }

        private CoordinatedTournamentStore peer() {
            return new CoordinatedTournamentStore(delegate, loadedSnapshotGate, commitCalls);
        }

        @Override
        public Optional<StoredTournament> load(TournamentId tournamentId) {
            Optional<StoredTournament> loaded = delegate.load(tournamentId);
            await(loadedSnapshotGate);
            return loaded;
        }

        @Override
        public Optional<TournamentWriteResult> findCommand(TournamentId tournamentId, UUID commandId) {
            return delegate.findCommand(tournamentId, commandId);
        }

        @Override
        public TournamentWriteResult commit(TournamentCommit commit) {
            commitCalls.incrementAndGet();
            return delegate.commit(commit);
        }

        @Override
        public List<TournamentEventEnvelope> eventsAfter(TournamentId tournamentId, long sequenceExclusive) {
            return delegate.eventsAfter(tournamentId, sequenceExclusive);
        }

        private int commitCalls() {
            return commitCalls.get();
        }
    }
}
