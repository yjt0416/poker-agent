package com.agenttavern.game.hand;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

public record HandId(UUID value) {

    public HandId {
        requireNonNull(value, "value");
    }

    public static HandId random() {
        return new HandId(UUID.randomUUID());
    }
}
