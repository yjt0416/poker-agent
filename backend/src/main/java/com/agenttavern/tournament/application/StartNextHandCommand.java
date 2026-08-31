package com.agenttavern.tournament.application;

import static java.util.Objects.requireNonNull;

import com.agenttavern.game.hand.HandId;
import com.agenttavern.tournament.TournamentId;
import java.util.UUID;

/** Starts the next hand at an expected tournament aggregate version. */
public record StartNextHandCommand(
        UUID commandId, TournamentId tournamentId, long expectedVersion, HandId handId) {

    public StartNextHandCommand {
        requireNonNull(commandId, "commandId");
        requireNonNull(tournamentId, "tournamentId");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expected version must be non-negative");
        }
        requireNonNull(handId, "handId");
    }
}
