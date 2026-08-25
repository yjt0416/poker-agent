package com.agenttavern.game.hand;

import static java.util.Objects.requireNonNull;

import com.agenttavern.game.betting.PlayerId;

public record PlayerStack(PlayerId playerId, int seatIndex, long chips) {

    public PlayerStack {
        requireNonNull(playerId, "playerId");
        if (seatIndex < 0 || seatIndex > 5) {
            throw new IllegalArgumentException("seatIndex must be between 0 and 5");
        }
        if (chips <= 0) {
            throw new IllegalArgumentException("chips must be positive");
        }
    }
}
