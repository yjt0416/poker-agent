package com.agenttavern.tournament.application;

import static java.util.Objects.requireNonNull;

import com.agenttavern.game.card.Deck;
import com.agenttavern.game.hand.HandId;
import com.agenttavern.tournament.Tournament;
import com.agenttavern.tournament.TournamentEvent;
import com.agenttavern.tournament.TournamentEventEnvelope;
import com.agenttavern.tournament.TournamentId;
import com.agenttavern.tournament.TournamentTransition;
import com.agenttavern.tournament.port.StoredTournament;
import com.agenttavern.tournament.port.TournamentCommit;
import com.agenttavern.tournament.port.TournamentStore;
import com.agenttavern.tournament.port.TournamentWriteResult;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/** Coordinates idempotent commands with the pure tournament aggregate and atomic persistence port. */
public final class TournamentCommandService {

    private final TournamentStore store;
    private final Clock clock;
    private final Supplier<Deck> deckSupplier;
    // JVM-local keyed lock registry. Values are retained to avoid unsafe monitor-removal races.
    private final ConcurrentMap<TournamentId, Object> tournamentLocks = new ConcurrentHashMap<>();

    public TournamentCommandService(TournamentStore store, Clock clock, Supplier<Deck> deckSupplier) {
        this.store = requireNonNull(store, "store");
        this.clock = requireNonNull(clock, "clock");
        this.deckSupplier = requireNonNull(deckSupplier, "deckSupplier");
    }

    public TournamentExecution create(CreateTournamentCommand command) {
        requireNonNull(command, "command");
        return withTournamentLock(command.tournamentId(), () -> createLocked(command));
    }

    public TournamentExecution act(ActInTournamentCommand command) {
        requireNonNull(command, "command");
        return withTournamentLock(command.tournamentId(), () -> actLocked(command));
    }

    public TournamentExecution startNextHand(StartNextHandCommand command) {
        requireNonNull(command, "command");
        return withTournamentLock(command.tournamentId(), () -> startNextHandLocked(command));
    }

    private TournamentExecution createLocked(CreateTournamentCommand command) {
        Optional<TournamentExecution> receipt = findReceipt(command.tournamentId(), command.commandId());
        if (receipt.isPresent()) {
            return receipt.get();
        }

        if (store.load(command.tournamentId()).isPresent()) {
            throw new ConcurrentTournamentUpdateException(command.tournamentId(), 0);
        }
        TournamentTransition transition = Tournament.start(
                command.tournamentId(),
                command.mode(),
                command.entrants(),
                command.firstButtonSeat(),
                command.firstHandId(),
                deckSupplier.get());
        return commit(command.commandId(), 0, 0, transition);
    }

    private TournamentExecution actLocked(ActInTournamentCommand command) {
        Optional<TournamentExecution> receipt = findReceipt(command.tournamentId(), command.commandId());
        if (receipt.isPresent()) {
            return receipt.get();
        }

        StoredTournament storedTournament = loadExpected(command.tournamentId(), command.expectedVersion());
        TournamentTransition transition = Tournament.restore(storedTournament.checkpoint())
                .act(command.actorId(), command.action());
        return commit(command.commandId(), storedTournament.version(), storedTournament.lastSequence(), transition);
    }

    private TournamentExecution startNextHandLocked(StartNextHandCommand command) {
        Optional<TournamentExecution> receipt = findReceipt(command.tournamentId(), command.commandId());
        if (receipt.isPresent()) {
            return receipt.get();
        }

        StoredTournament storedTournament = loadExpected(command.tournamentId(), command.expectedVersion());
        TournamentTransition transition = Tournament.restore(storedTournament.checkpoint())
                .startNextHand(command.handId(), deckSupplier.get());
        return commit(command.commandId(), storedTournament.version(), storedTournament.lastSequence(), transition);
    }

    private <T> T withTournamentLock(TournamentId tournamentId, Supplier<T> operation) {
        Object lock = tournamentLocks.computeIfAbsent(tournamentId, ignored -> new Object());
        synchronized (lock) {
            return operation.get();
        }
    }

    private Optional<TournamentExecution> findReceipt(TournamentId tournamentId, UUID commandId) {
        return store.findCommand(tournamentId, commandId).map(TournamentCommandService::execution);
    }

    private StoredTournament loadExpected(TournamentId tournamentId, long expectedVersion) {
        StoredTournament storedTournament = store.load(tournamentId)
                .orElseThrow(() -> new TournamentNotFoundException(tournamentId));
        if (storedTournament.version() != expectedVersion) {
            throw new ConcurrentTournamentUpdateException(tournamentId, expectedVersion);
        }
        return storedTournament;
    }

    private TournamentExecution commit(
            UUID commandId,
            long expectedVersion,
            long lastSequence,
            TournamentTransition transition) {
        long aggregateVersion = Math.incrementExact(expectedVersion);
        Instant occurredAt = clock.instant();
        List<TournamentEventEnvelope> events = envelope(
                transition.tournament().id(),
                lastSequence,
                aggregateVersion,
                occurredAt,
                transition.events());
        TournamentWriteResult writeResult;
        try {
            writeResult = store.commit(new TournamentCommit(
                    commandId, expectedVersion, transition.tournament().checkpoint(), events));
        } catch (ConcurrentModificationException exception) {
            throw new ConcurrentTournamentUpdateException(transition.tournament().id(), expectedVersion);
        }
        return execution(writeResult);
    }

    private static List<TournamentEventEnvelope> envelope(
            TournamentId tournamentId,
            long lastSequence,
            long aggregateVersion,
            Instant occurredAt,
            List<TournamentEvent> events) {
        List<TournamentEventEnvelope> envelopes = new ArrayList<>(events.size());
        long sequence = lastSequence;
        for (TournamentEvent event : events) {
            sequence = Math.incrementExact(sequence);
            Optional<HandId> handId = event instanceof TournamentEvent.HandEventRecorded recorded
                    ? Optional.of(recorded.handEvent().handId())
                    : Optional.empty();
            envelopes.add(new TournamentEventEnvelope(
                    tournamentId, handId, sequence, aggregateVersion, occurredAt, event));
        }
        return List.copyOf(envelopes);
    }

    private static TournamentExecution execution(TournamentWriteResult result) {
        StoredTournament storedTournament = result.storedTournament();
        return new TournamentExecution(
                storedTournament.checkpoint(),
                storedTournament.version(),
                storedTournament.lastSequence(),
                result.events());
    }
}
