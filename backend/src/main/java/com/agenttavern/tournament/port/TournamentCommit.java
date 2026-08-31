package com.agenttavern.tournament.port;

import static java.util.Objects.requireNonNull;

import com.agenttavern.tournament.TournamentCheckpoint;
import com.agenttavern.tournament.TournamentEventEnvelope;
import java.util.List;
import java.util.UUID;

/** One all-or-nothing tournament state transition requested by the application service. */
public record TournamentCommit(
        UUID commandId,
        long expectedVersion,
        TournamentCheckpoint checkpoint,
        List<TournamentEventEnvelope> events) {

    public TournamentCommit {
        requireNonNull(commandId, "commandId");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expected version must be non-negative");
        }
        requireNonNull(checkpoint, "checkpoint");
        requireNonNull(events, "events");
        events = List.copyOf(events);
    }
}
