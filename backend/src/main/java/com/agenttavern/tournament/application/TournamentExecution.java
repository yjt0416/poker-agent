package com.agenttavern.tournament.application;

import static java.util.Objects.requireNonNull;

import com.agenttavern.tournament.TournamentCheckpoint;
import com.agenttavern.tournament.TournamentEventEnvelope;
import java.util.List;

/** Application-facing result of a command, including its original command event batch. */
public record TournamentExecution(
        TournamentCheckpoint checkpoint,
        long version,
        long lastSequence,
        List<TournamentEventEnvelope> events) {

    public TournamentExecution {
        requireNonNull(checkpoint, "checkpoint");
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        if (lastSequence < 0) {
            throw new IllegalArgumentException("last sequence must be non-negative");
        }
        requireNonNull(events, "events");
        events = List.copyOf(events);
    }
}
