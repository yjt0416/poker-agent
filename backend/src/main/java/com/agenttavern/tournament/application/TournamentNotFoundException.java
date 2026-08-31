package com.agenttavern.tournament.application;

import static java.util.Objects.requireNonNull;

import com.agenttavern.tournament.TournamentId;

/** Raised when a non-create command targets no stored tournament. */
public final class TournamentNotFoundException extends RuntimeException {

    private final TournamentId tournamentId;

    public TournamentNotFoundException(TournamentId tournamentId) {
        super("tournament not found: " + requireNonNull(tournamentId, "tournamentId").value());
        this.tournamentId = tournamentId;
    }

    public TournamentId tournamentId() {
        return tournamentId;
    }
}
