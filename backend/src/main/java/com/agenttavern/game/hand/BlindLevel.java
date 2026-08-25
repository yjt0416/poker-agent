package com.agenttavern.game.hand;

public record BlindLevel(long smallBlind, long bigBlind) {

    public BlindLevel {
        if (smallBlind <= 0 || bigBlind <= 0) {
            throw new IllegalArgumentException("blinds must be positive");
        }
        if (bigBlind < Math.multiplyExact(smallBlind, 2)) {
            throw new IllegalArgumentException("bigBlind must be at least twice smallBlind");
        }
    }
}
