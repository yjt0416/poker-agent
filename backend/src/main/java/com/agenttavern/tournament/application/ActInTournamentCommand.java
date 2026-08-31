package com.agenttavern.tournament.application;

import static java.util.Objects.requireNonNull;

import com.agenttavern.game.betting.PlayerAction;
import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.tournament.TournamentId;
import java.util.UUID;

/** Applies one player action at an expected tournament aggregate version. */
public record ActInTournamentCommand(
        UUID commandId,
        TournamentId tournamentId,
        long expectedVersion,
        PlayerId actorId,
        PlayerAction action) {

    public ActInTournamentCommand {
        requireNonNull(commandId, "commandId");
        requireNonNull(tournamentId, "tournamentId");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expected version must be non-negative");
        }
        requireNonNull(actorId, "actorId");
        requireNonNull(action, "action");
    }
}
