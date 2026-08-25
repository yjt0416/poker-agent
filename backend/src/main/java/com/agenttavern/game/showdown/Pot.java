package com.agenttavern.game.showdown;

import com.agenttavern.game.betting.PlayerId;
import java.util.Set;

public record Pot(long amount, Set<PlayerId> eligiblePlayers) {

    private static final int TABLE_SIZE = 6;

    public Pot {
        if (amount <= 0) {
            throw new IllegalArgumentException("pot amount must be positive");
        }
        if (eligiblePlayers == null || eligiblePlayers.isEmpty()) {
            throw new IllegalArgumentException("eligiblePlayers must not be null or empty");
        }
        if (eligiblePlayers.size() > TABLE_SIZE) {
            throw new IllegalArgumentException("eligiblePlayers must contain at most 6 players");
        }
        if (eligiblePlayers.stream().anyMatch(playerId -> playerId == null)) {
            throw new IllegalArgumentException("eligiblePlayers must not contain null");
        }
        eligiblePlayers = Set.copyOf(eligiblePlayers);
    }
}
