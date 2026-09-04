package com.agenttavern.agents;

import static java.util.Objects.requireNonNull;

import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.betting.PlayerStatus;

public record ObservedSeat(PlayerId playerId, int seatIndex, long stack, long committed, PlayerStatus status) {
    public ObservedSeat {
        requireNonNull(playerId, "playerId");
        requireNonNull(status, "status");
        if (seatIndex < 0 || seatIndex > 5 || stack < 0 || committed < 0) {
            throw new IllegalArgumentException("invalid observed seat");
        }
    }
}
