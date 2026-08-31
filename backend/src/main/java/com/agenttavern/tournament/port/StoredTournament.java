package com.agenttavern.tournament.port;

import static java.util.Objects.requireNonNull;

import com.agenttavern.tournament.TournamentCheckpoint;

/** The latest durable checkpoint and stream position for a tournament. */
public record StoredTournament(TournamentCheckpoint checkpoint, long version, long lastSequence) {

    public StoredTournament {
        requireNonNull(checkpoint, "checkpoint");
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        if (lastSequence < 0) {
            throw new IllegalArgumentException("last sequence must be non-negative");
        }
    }
}
