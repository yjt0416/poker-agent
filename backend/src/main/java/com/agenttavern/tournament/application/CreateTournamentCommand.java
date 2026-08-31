package com.agenttavern.tournament.application;

import static java.util.Objects.requireNonNull;

import com.agenttavern.game.hand.HandId;
import com.agenttavern.tournament.TournamentEntrant;
import com.agenttavern.tournament.TournamentId;
import com.agenttavern.tournament.TournamentMode;
import java.util.List;
import java.util.UUID;

/** Starts a new tournament and its first hand under a caller-supplied idempotency key. */
public record CreateTournamentCommand(
        UUID commandId,
        TournamentId tournamentId,
        TournamentMode mode,
        List<TournamentEntrant> entrants,
        int firstButtonSeat,
        HandId firstHandId) {

    public CreateTournamentCommand {
        requireNonNull(commandId, "commandId");
        requireNonNull(tournamentId, "tournamentId");
        requireNonNull(mode, "mode");
        requireNonNull(entrants, "entrants");
        entrants = List.copyOf(entrants);
        entrants.forEach(entrant -> requireNonNull(entrant, "entrant"));
        if (firstButtonSeat < 0 || firstButtonSeat > 5) {
            throw new IllegalArgumentException("first button seat must be between 0 and 5");
        }
        requireNonNull(firstHandId, "firstHandId");
    }
}
