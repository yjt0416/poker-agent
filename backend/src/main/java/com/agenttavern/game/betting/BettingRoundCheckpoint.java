package com.agenttavern.game.betting;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Set;

public record BettingRoundCheckpoint(
        List<SeatState> seats,
        Street street,
        long currentBet,
        long lastFullRaiseSize,
        Set<PlayerId> pendingAction,
        Set<PlayerId> raiseRights,
        PlayerId actorId) {

    public BettingRoundCheckpoint {
        requireNonNull(seats, "seats");
        requireNonNull(street, "street");
        requireNonNull(pendingAction, "pendingAction");
        requireNonNull(raiseRights, "raiseRights");
        seats = List.copyOf(seats);
        pendingAction = Set.copyOf(pendingAction);
        raiseRights = Set.copyOf(raiseRights);
    }
}
