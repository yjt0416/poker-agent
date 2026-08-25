package com.agenttavern.game.card;

import java.util.Objects;

public record Card(Suit suit, Rank rank) {

    public Card {
        Objects.requireNonNull(suit, "suit");
        Objects.requireNonNull(rank, "rank");
    }
}
