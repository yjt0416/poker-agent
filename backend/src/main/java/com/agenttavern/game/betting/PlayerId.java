package com.agenttavern.game.betting;

import java.util.UUID;

import static java.util.Objects.requireNonNull;

public record PlayerId(UUID value) {

    public PlayerId {
        requireNonNull(value, "value");
    }

    public static PlayerId random() {
        return new PlayerId(UUID.randomUUID());
    }
}
