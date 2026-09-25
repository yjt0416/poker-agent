package com.agenttavern.web;

import com.agenttavern.tournament.TournamentEventEnvelope;
import com.agenttavern.tournament.TournamentId;
import com.agenttavern.tournament.port.StoredTournament;
import com.agenttavern.tournament.port.TournamentCommit;
import com.agenttavern.tournament.port.TournamentStore;
import com.agenttavern.tournament.port.TournamentWriteResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Process-local transactional store used by the zero-setup local profile. */
final class RuntimeTournamentStore implements TournamentStore {
    private State state = State.empty();

    synchronized <T> T transaction(java.util.function.Supplier<T> work) {
        State before = state;
        try { return work.get(); }
        catch (RuntimeException error) { state = before; throw error; }
    }

    synchronized void removeAll(Collection<TournamentId> ids) {
        Map<TournamentId, StoredTournament> tournaments = new LinkedHashMap<>(state.tournaments());
        Map<TournamentId, List<TournamentEventEnvelope>> events = copyEvents(state.events());
        Map<TournamentId, Map<UUID, TournamentWriteResult>> receipts = copyReceipts(state.receipts());
        ids.forEach(id -> { tournaments.remove(id); events.remove(id); receipts.remove(id); });
        state = new State(Map.copyOf(tournaments), Map.copyOf(events), Map.copyOf(receipts));
    }

    @Override
    public synchronized Optional<StoredTournament> load(TournamentId tournamentId) {
        return Optional.ofNullable(state.tournaments().get(tournamentId));
    }

    @Override
    public synchronized Optional<TournamentWriteResult> findCommand(TournamentId tournamentId, UUID commandId) {
        return Optional.ofNullable(state.receipts().getOrDefault(tournamentId, Map.of()).get(commandId))
                .map(RuntimeTournamentStore::alreadyApplied);
    }

    @Override
    public synchronized TournamentWriteResult commit(TournamentCommit commit) {
        TournamentId id = commit.checkpoint().id();
        TournamentWriteResult receipt = state.receipts().getOrDefault(id, Map.of()).get(commit.commandId());
        if (receipt != null) return alreadyApplied(receipt);

        StoredTournament current = state.tournaments().get(id);
        validate(commit, current);
        long nextVersion = Math.incrementExact(commit.expectedVersion());
        long nextSequence = current == null ? commit.events().size()
                : Math.addExact(current.lastSequence(), commit.events().size());
        StoredTournament stored = new StoredTournament(commit.checkpoint(), nextVersion, nextSequence);
        TournamentWriteResult result = new TournamentWriteResult(
                TournamentWriteResult.WriteStatus.APPLIED, stored, commit.events());

        Map<TournamentId, StoredTournament> tournaments = new LinkedHashMap<>(state.tournaments());
        tournaments.put(id, stored);
        Map<TournamentId, List<TournamentEventEnvelope>> eventStreams = copyEvents(state.events());
        List<TournamentEventEnvelope> stream = new ArrayList<>(eventStreams.getOrDefault(id, List.of()));
        stream.addAll(commit.events());
        eventStreams.put(id, List.copyOf(stream));
        Map<TournamentId, Map<UUID, TournamentWriteResult>> receipts = copyReceipts(state.receipts());
        Map<UUID, TournamentWriteResult> tournamentReceipts = new LinkedHashMap<>(receipts.getOrDefault(id, Map.of()));
        tournamentReceipts.put(commit.commandId(), result);
        receipts.put(id, Map.copyOf(tournamentReceipts));
        state = new State(Map.copyOf(tournaments), Map.copyOf(eventStreams), Map.copyOf(receipts));
        return result;
    }

    @Override
    public synchronized List<TournamentEventEnvelope> eventsAfter(TournamentId id, long sequenceExclusive) {
        if (sequenceExclusive < 0) throw new IllegalArgumentException("sequence must be non-negative");
        return state.events().getOrDefault(id, List.of()).stream()
                .filter(event -> event.sequence() > sequenceExclusive)
                .toList();
    }

    private static void validate(TournamentCommit commit, StoredTournament current) {
        if (current == null && commit.expectedVersion() != 0) {
            throw new ConcurrentModificationException("tournament does not exist");
        }
        if (current != null && current.version() != commit.expectedVersion()) {
            throw new ConcurrentModificationException("tournament version has changed");
        }
        long expectedSequence = current == null ? 0 : current.lastSequence();
        long version = Math.incrementExact(commit.expectedVersion());
        for (TournamentEventEnvelope event : commit.events()) {
            expectedSequence = Math.incrementExact(expectedSequence);
            if (!event.tournamentId().equals(commit.checkpoint().id())
                    || event.sequence() != expectedSequence
                    || event.aggregateVersion() != version) {
                throw new IllegalArgumentException("invalid event envelope sequence");
            }
        }
    }

    private static TournamentWriteResult alreadyApplied(TournamentWriteResult receipt) {
        return new TournamentWriteResult(
                TournamentWriteResult.WriteStatus.ALREADY_APPLIED,
                receipt.storedTournament(),
                receipt.events());
    }

    private static Map<TournamentId, List<TournamentEventEnvelope>> copyEvents(
            Map<TournamentId, List<TournamentEventEnvelope>> source) {
        Map<TournamentId, List<TournamentEventEnvelope>> copy = new LinkedHashMap<>();
        source.forEach((id, events) -> copy.put(id, List.copyOf(events)));
        return copy;
    }

    private static Map<TournamentId, Map<UUID, TournamentWriteResult>> copyReceipts(
            Map<TournamentId, Map<UUID, TournamentWriteResult>> source) {
        Map<TournamentId, Map<UUID, TournamentWriteResult>> copy = new LinkedHashMap<>();
        source.forEach((id, receipts) -> copy.put(id, Map.copyOf(receipts)));
        return copy;
    }

    private record State(
            Map<TournamentId, StoredTournament> tournaments,
            Map<TournamentId, List<TournamentEventEnvelope>> events,
            Map<TournamentId, Map<UUID, TournamentWriteResult>> receipts) {
        static State empty() { return new State(Map.of(), Map.of(), Map.of()); }
    }
}
