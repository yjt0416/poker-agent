package com.agenttavern.tournament;

import static java.util.Objects.requireNonNull;

import com.agenttavern.game.betting.PlayerId;

public record TournamentEntrant(PlayerId playerId, int seatIndex) {

    public TournamentEntrant {
        requireNonNull(playerId, "playerId");
        validateSeatIndex(seatIndex);
    }

    private static void validateSeatIndex(int seatIndex) {
        if (seatIndex < 0 || seatIndex > 5) {
            throw new IllegalArgumentException("seat index must be between 0 and 5");
        }
    }
}
