package com.agenttavern.tournament.port;

import static java.util.Objects.requireNonNull;

import com.agenttavern.tournament.TournamentEventEnvelope;
import java.util.List;

/** The durable result of applying a tournament command or reading its prior receipt. */
public record TournamentWriteResult(
        WriteStatus status, StoredTournament storedTournament, List<TournamentEventEnvelope> events) {

    public TournamentWriteResult {
        requireNonNull(status, "status");
        requireNonNull(storedTournament, "storedTournament");
        requireNonNull(events, "events");
        events = List.copyOf(events);
    }

    public enum WriteStatus {
        APPLIED,
        ALREADY_APPLIED
    }
}
