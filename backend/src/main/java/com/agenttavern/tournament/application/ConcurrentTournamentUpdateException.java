package com.agenttavern.tournament.application;

import static java.util.Objects.requireNonNull;

import com.agenttavern.tournament.TournamentId;

/** The command's expected aggregate version no longer matches the stored tournament. */
public final class ConcurrentTournamentUpdateException extends RuntimeException {

    private final TournamentId tournamentId;
    private final long expectedVersion;

    public ConcurrentTournamentUpdateException(TournamentId tournamentId, long expectedVersion) {
        super("concurrent tournament update for "
                + requireNonNull(tournamentId, "tournamentId").value()
                + " at expected version "
                + expectedVersion);
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expected version must be non-negative");
        }
        this.tournamentId = tournamentId;
        this.expectedVersion = expectedVersion;
    }

    public TournamentId tournamentId() {
        return tournamentId;
    }

    public long expectedVersion() {
        return expectedVersion;
    }
}
