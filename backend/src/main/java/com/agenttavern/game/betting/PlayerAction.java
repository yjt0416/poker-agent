package com.agenttavern.game.betting;

import static java.util.Objects.requireNonNull;

public record PlayerAction(ActionType type, long amount) {

    public PlayerAction {
        requireNonNull(type, "type");
        if (type == ActionType.RAISE || type == ActionType.ALL_IN) {
            if (amount <= 0) {
                throw new IllegalArgumentException("raise and all-in targets must be positive");
            }
        } else if (amount != 0) {
            throw new IllegalArgumentException("fold, check, and call amounts must be zero");
        }
    }

    public static PlayerAction fold() {
        return new PlayerAction(ActionType.FOLD, 0);
    }

    public static PlayerAction check() {
        return new PlayerAction(ActionType.CHECK, 0);
    }

    public static PlayerAction call() {
        return new PlayerAction(ActionType.CALL, 0);
    }

    public static PlayerAction raiseTo(long target) {
        return new PlayerAction(ActionType.RAISE, target);
    }

    public static PlayerAction allIn(long target) {
        return new PlayerAction(ActionType.ALL_IN, target);
    }
}
