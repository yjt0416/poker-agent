package com.agenttavern.tournament.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenttavern.game.betting.PlayerAction;
import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.card.Deck;
import com.agenttavern.game.hand.HandId;
import com.agenttavern.tournament.Tournament;
import com.agenttavern.tournament.TournamentEntrant;
import com.agenttavern.tournament.TournamentEventEnvelope;
import com.agenttavern.tournament.TournamentId;
import com.agenttavern.tournament.TournamentMode;
import com.agenttavern.tournament.port.StoredTournament;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TournamentCommandServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-31T12:00:00Z");

    private final TournamentId tournamentId = tournament(1);
    private final AtomicInteger suppliedDecks = new AtomicInteger();
    private final CountingClock clock = new CountingClock(FIXED_INSTANT, ZoneOffset.UTC);
    private InMemoryTournamentStore store;
    private TournamentCommandService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryTournamentStore();
        service = new TournamentCommandService(
                store,
                clock,
                () -> {
                    suppliedDecks.incrementAndGet();
                    return Deck.standard();
                });
    }

    @Test
    void acceptedCommandAssignsContinuousSequenceAndOneVersion() {
        TournamentExecution created = createTournament();
        PlayerId actorId = currentActor(created);

        TournamentExecution result = service.act(new ActInTournamentCommand(
                UUID.randomUUID(), tournamentId, 1L, actorId, PlayerAction.call()));

        assertThat(result.version()).isEqualTo(2L);
        assertThat(result.events()).extracting(TournamentEventEnvelope::sequence)
                .containsExactly(5L);
        assertThat(result.events()).allSatisfy(event -> {
            assertThat(event.aggregateVersion()).isEqualTo(2L);
            assertThat(event.occurredAt()).isEqualTo(FIXED_INSTANT);
        });
        assertThat(result.lastSequence()).isEqualTo(5L);
        assertThat(store.eventCount(tournamentId)).isEqualTo(5);
    }

    @Test
    void duplicateCreateCommandReturnsOriginalReceiptWithoutGeneratingANewDeckOrEvents() {
        CreateTournamentCommand command = createCommand(UUID.randomUUID());

        TournamentExecution first = service.create(command);
        TournamentExecution duplicate = service.create(command);

        assertThat(duplicate).isEqualTo(first);
        assertThat(store.eventCount(tournamentId)).isEqualTo(first.events().size());
        assertThat(suppliedDecks).hasValue(1);
        assertThat(clock.instantCalls()).isEqualTo(1);
    }

    @Test
    void simultaneousDuplicateCreatesProduceOneReceiptWithoutExtraDeckOrClockSideEffects()
            throws Exception {
        BarrierDeckSupplier racingDeckSupplier = new BarrierDeckSupplier();
        CountingClock racingClock = new CountingClock(FIXED_INSTANT, ZoneOffset.UTC);
        TournamentCommandService racingService = new TournamentCommandService(
                new InMemoryTournamentStore(), racingClock, racingDeckSupplier);
        CreateTournamentCommand command = createCommand(UUID.randomUUID());

        List<TournamentExecution> executions = concurrently(
                () -> racingService.create(command),
                () -> racingService.create(command));

        assertThat(executions).allSatisfy(execution -> assertThat(execution)
                .isEqualTo(executions.getFirst()));
        assertThat(racingDeckSupplier.calls()).isEqualTo(1);
        assertThat(racingClock.instantCalls()).isEqualTo(1);
    }

    @Test
    void simultaneousDuplicateActionsProduceOneReceiptWithoutAnExtraClockSideEffect()
            throws Exception {
        TournamentExecution created = createTournament();
        BarrierCountingClock racingClock = new BarrierCountingClock(FIXED_INSTANT, ZoneOffset.UTC);
        AtomicInteger racingDeckCalls = new AtomicInteger();
        TournamentCommandService racingService = new TournamentCommandService(
                store,
                racingClock,
                () -> {
                    racingDeckCalls.incrementAndGet();
                    return Deck.standard();
                });
        ActInTournamentCommand command = new ActInTournamentCommand(
                UUID.randomUUID(), tournamentId, created.version(), currentActor(created), PlayerAction.call());

        List<TournamentExecution> executions = concurrently(
                () -> racingService.act(command),
                () -> racingService.act(command));

        assertThat(executions).allSatisfy(execution -> assertThat(execution)
                .isEqualTo(executions.getFirst()));
        assertThat(store.eventCount(tournamentId)).isEqualTo(5);
        assertThat(racingDeckCalls).hasValue(0);
        assertThat(racingClock.instantCalls()).isEqualTo(1);
    }

    @Test
    void duplicateActCommandReturnsOriginalReceiptWithoutNewEvents() {
        TournamentExecution created = createTournament();
        ActInTournamentCommand command = new ActInTournamentCommand(
                UUID.randomUUID(), tournamentId, created.version(), currentActor(created), PlayerAction.call());

        TournamentExecution first = service.act(command);
        TournamentExecution duplicate = service.act(command);

        assertThat(duplicate).isEqualTo(first);
        assertThat(store.eventCount(tournamentId)).isEqualTo(5);
    }

    @Test
    void staleExpectedVersionThrowsConcurrentUpdate() {
        TournamentExecution created = createTournament();

        assertThatThrownBy(() -> service.act(new ActInTournamentCommand(
                        UUID.randomUUID(), tournamentId, 0L, currentActor(created), PlayerAction.call())))
                .isInstanceOf(ConcurrentTournamentUpdateException.class)
                .hasMessageContaining(tournamentId.value().toString())
                .hasMessageContaining("0");
    }

    @Test
    void failedCommitChangesNeitherSnapshotEventsNorReceipt() {
        TournamentExecution created = createTournament();
        StoredTournament before = store.load(tournamentId).orElseThrow();
        List<TournamentEventEnvelope> eventsBefore = store.eventsAfter(tournamentId, 0);
        int receiptsBefore = store.receiptCount();
        store.failNextCommit();

        assertThatThrownBy(() -> service.act(new ActInTournamentCommand(
                        UUID.randomUUID(), tournamentId, created.version(), currentActor(created), PlayerAction.call())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected commit failure");

        assertThat(store.load(tournamentId).orElseThrow()).isEqualTo(before);
        assertThat(store.eventsAfter(tournamentId, 0)).isEqualTo(eventsBefore);
        assertThat(store.receiptCount()).isEqualTo(receiptsBefore);
    }

    @Test
    void envelopeUsesInjectedClock() {
        TournamentExecution result = createTournament();

        assertThat(result.events()).extracting(TournamentEventEnvelope::occurredAt)
                .containsOnly(FIXED_INSTANT);
    }

    @Test
    void unknownTournamentThrowsStableNotFoundError() {
        TournamentId unknownTournament = tournament(99);

        assertThatThrownBy(() -> service.act(new ActInTournamentCommand(
                        UUID.randomUUID(), unknownTournament, 0L, player(1), PlayerAction.call())))
                .isInstanceOf(TournamentNotFoundException.class)
                .hasMessage("tournament not found: " + unknownTournament.value());
    }

    private TournamentExecution createTournament() {
        return service.create(createCommand(UUID.randomUUID()));
    }

    private CreateTournamentCommand createCommand(UUID commandId) {
        return new CreateTournamentCommand(
                commandId, tournamentId, TournamentMode.PLAYER, sixEntrants(), 0, hand(1));
    }

    private static PlayerId currentActor(TournamentExecution execution) {
        return Tournament.restore(execution.checkpoint()).currentHand().actor().playerId();
    }

    private static List<TournamentEntrant> sixEntrants() {
        return List.of(
                new TournamentEntrant(player(1), 0),
                new TournamentEntrant(player(2), 1),
                new TournamentEntrant(player(3), 2),
                new TournamentEntrant(player(4), 3),
                new TournamentEntrant(player(5), 4),
                new TournamentEntrant(player(6), 5));
    }

    private static PlayerId player(long id) {
        return new PlayerId(new UUID(0, id));
    }

    private static HandId hand(long id) {
        return new HandId(new UUID(1, id));
    }

    private static TournamentId tournament(long id) {
        return new TournamentId(new UUID(2, id));
    }

    private static List<TournamentExecution> concurrently(
            Supplier<TournamentExecution> first, Supplier<TournamentExecution> second) throws Exception {
        CyclicBarrier startingGate = new CyclicBarrier(3);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<TournamentExecution> firstExecution = executor.submit(() -> {
                await(startingGate);
                return first.get();
            });
            Future<TournamentExecution> secondExecution = executor.submit(() -> {
                await(startingGate);
                return second.get();
            });
            await(startingGate);
            return List.of(
                    firstExecution.get(5, TimeUnit.SECONDS),
                    secondExecution.get(5, TimeUnit.SECONDS));
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting concurrent test barrier", exception);
        } catch (BrokenBarrierException exception) {
            throw new AssertionError("concurrent test barrier broke", exception);
        }
    }

    private static final class CountingClock extends Clock {
        private final Instant instant;
        private final ZoneId zone;
        private int instantCalls;

        private CountingClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            return new CountingClock(instant, requestedZone);
        }

        @Override
        public Instant instant() {
            instantCalls = Math.incrementExact(instantCalls);
            return instant;
        }

        private int instantCalls() {
            return instantCalls;
        }
    }

    private static final class BarrierDeckSupplier implements Supplier<Deck> {
        private static final long PEER_ARRIVAL_TIMEOUT_SECONDS = 2;

        private final AtomicInteger calls = new AtomicInteger();
        private final CyclicBarrier duplicateCallBarrier = new CyclicBarrier(2);

        @Override
        public Deck get() {
            calls.incrementAndGet();
            try {
                duplicateCallBarrier.await(PEER_ARRIVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException expectedWhenSerialized) {
                // A per-tournament lock correctly prevents a duplicate from reaching this supplier.
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while awaiting duplicate deck request", exception);
            } catch (BrokenBarrierException exception) {
                throw new AssertionError("duplicate deck request barrier broke", exception);
            }
            return Deck.standard();
        }

        private int calls() {
            return calls.get();
        }
    }

    private static final class BarrierCountingClock extends Clock {
        private static final long PEER_ARRIVAL_TIMEOUT_SECONDS = 2;

        private final Instant instant;
        private final ZoneId zone;
        private final AtomicInteger instantCalls = new AtomicInteger();
        private final CyclicBarrier duplicateCallBarrier = new CyclicBarrier(2);

        private BarrierCountingClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            return new BarrierCountingClock(instant, requestedZone);
        }

        @Override
        public Instant instant() {
            instantCalls.incrementAndGet();
            try {
                duplicateCallBarrier.await(PEER_ARRIVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException expectedWhenSerialized) {
                // A per-tournament lock correctly prevents a duplicate from consulting the clock.
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while awaiting duplicate clock request", exception);
            } catch (BrokenBarrierException exception) {
                throw new AssertionError("duplicate clock request barrier broke", exception);
            }
            return instant;
        }

        private int instantCalls() {
            return instantCalls.get();
        }
    }
}
