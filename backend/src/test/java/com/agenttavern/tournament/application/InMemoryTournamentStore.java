package com.agenttavern.tournament.application;

import com.agenttavern.tournament.TournamentEventEnvelope;
import com.agenttavern.tournament.TournamentId;
import com.agenttavern.tournament.port.StoredTournament;
import com.agenttavern.tournament.port.TournamentCommit;
import com.agenttavern.tournament.port.TournamentStore;
import com.agenttavern.tournament.port.TournamentWriteResult;
import com.agenttavern.tournament.port.TournamentWriteResult.WriteStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory test double for the atomic persistence contract, shared by command-service
 * acceptance tests.
 */
public final class InMemoryTournamentStore implements TournamentStore {

    private State state = State.empty();
    private boolean failNextCommit;

    @Override
    public synchronized Optional<StoredTournament> load(TournamentId tournamentId) {
        return Optional.ofNullable(state.tournaments().get(tournamentId));
    }

    @Override
    public synchronized Optional<TournamentWriteResult> findCommand(
            TournamentId tournamentId, UUID commandId) {
        TournamentWriteResult receipt = state.receipts()
                .getOrDefault(tournamentId, Map.of())
                .get(commandId);
        return Optional.ofNullable(receipt).map(InMemoryTournamentStore::alreadyApplied);
    }

    @Override
    public synchronized TournamentWriteResult commit(TournamentCommit commit) {
        TournamentId tournamentId = commit.checkpoint().id();
        TournamentWriteResult existingReceipt = state.receipts()
                .getOrDefault(tournamentId, Map.of())
                .get(commit.commandId());
        if (existingReceipt != null) {
            return alreadyApplied(existingReceipt);
        }

        StoredTournament current = state.tournaments().get(tournamentId);
        validate(commit, current);
        if (failNextCommit) {
            failNextCommit = false;
            throw new IllegalStateException("injected commit failure");
        }

        long nextVersion = Math.incrementExact(commit.expectedVersion());
        long nextSequence = nextSequence(current, commit.events());
        StoredTournament nextStoredTournament = new StoredTournament(
                commit.checkpoint(), nextVersion, nextSequence);
        TournamentWriteResult receipt = new TournamentWriteResult(
                WriteStatus.APPLIED, nextStoredTournament, commit.events());

        Map<TournamentId, StoredTournament> tournaments = new LinkedHashMap<>(state.tournaments());
        tournaments.put(tournamentId, nextStoredTournament);
        Map<TournamentId, List<TournamentEventEnvelope>> events = copyEventStreams(state.events());
        List<TournamentEventEnvelope> tournamentEvents = new ArrayList<>(
                events.getOrDefault(tournamentId, List.of()));
        tournamentEvents.addAll(commit.events());
        events.put(tournamentId, List.copyOf(tournamentEvents));
        Map<TournamentId, Map<UUID, TournamentWriteResult>> receipts = copyReceipts(state.receipts());
        Map<UUID, TournamentWriteResult> tournamentReceipts = new LinkedHashMap<>(
                receipts.getOrDefault(tournamentId, Map.of()));
        tournamentReceipts.put(commit.commandId(), receipt);
        receipts.put(tournamentId, Map.copyOf(tournamentReceipts));

        state = new State(Map.copyOf(tournaments), Map.copyOf(events), Map.copyOf(receipts));
        return receipt;
    }

    @Override
    public synchronized List<TournamentEventEnvelope> eventsAfter(
            TournamentId tournamentId, long sequenceExclusive) {
        if (sequenceExclusive < 0) {
            throw new IllegalArgumentException("sequence exclusive must be non-negative");
        }
        return state.events().getOrDefault(tournamentId, List.of()).stream()
                .filter(event -> event.sequence() > sequenceExclusive)
                .toList();
    }

    synchronized void failNextCommit() {
        failNextCommit = true;
    }

    synchronized int eventCount(TournamentId tournamentId) {
        return state.events().getOrDefault(tournamentId, List.of()).size();
    }

    synchronized int receiptCount() {
        return state.receipts().values().stream().mapToInt(Map::size).sum();
    }

    private static void validate(TournamentCommit commit, StoredTournament current) {
        TournamentId tournamentId = commit.checkpoint().id();
        if (current == null) {
            if (commit.expectedVersion() != 0) {
                throw new java.util.ConcurrentModificationException("tournament does not exist");
            }
        } else if (current.version() != commit.expectedVersion()) {
            throw new java.util.ConcurrentModificationException("tournament version has changed");
        }

        long nextVersion = Math.incrementExact(commit.expectedVersion());
        long sequence = current == null ? 0 : current.lastSequence();
        for (TournamentEventEnvelope event : commit.events()) {
            if (!event.tournamentId().equals(tournamentId)) {
                throw new IllegalArgumentException("event tournament ID must match checkpoint ID");
            }
            sequence = Math.incrementExact(sequence);
            if (event.sequence() != sequence) {
                throw new IllegalArgumentException("event sequences must be continuous");
            }
            if (event.aggregateVersion() != nextVersion) {
                throw new IllegalArgumentException("event aggregate version must match the committed version");
            }
        }
    }

    private static long nextSequence(
            StoredTournament current, List<TournamentEventEnvelope> events) {
        long sequence = current == null ? 0 : current.lastSequence();
        for (TournamentEventEnvelope ignored : events) {
            sequence = Math.incrementExact(sequence);
        }
        return sequence;
    }

    private static TournamentWriteResult alreadyApplied(TournamentWriteResult receipt) {
        return new TournamentWriteResult(
                WriteStatus.ALREADY_APPLIED, receipt.storedTournament(), receipt.events());
    }

    private static Map<TournamentId, List<TournamentEventEnvelope>> copyEventStreams(
            Map<TournamentId, List<TournamentEventEnvelope>> source) {
        Map<TournamentId, List<TournamentEventEnvelope>> copy = new LinkedHashMap<>();
        source.forEach((tournamentId, events) -> copy.put(tournamentId, List.copyOf(events)));
        return copy;
    }

    private static Map<TournamentId, Map<UUID, TournamentWriteResult>> copyReceipts(
            Map<TournamentId, Map<UUID, TournamentWriteResult>> source) {
        Map<TournamentId, Map<UUID, TournamentWriteResult>> copy = new LinkedHashMap<>();
        source.forEach((tournamentId, receipts) ->
                copy.put(tournamentId, Map.copyOf(new LinkedHashMap<>(receipts))));
        return copy;
    }

    private record State(
            Map<TournamentId, StoredTournament> tournaments,
            Map<TournamentId, List<TournamentEventEnvelope>> events,
            Map<TournamentId, Map<UUID, TournamentWriteResult>> receipts) {

        static State empty() {
            return new State(Map.of(), Map.of(), Map.of());
        }
    }
}
