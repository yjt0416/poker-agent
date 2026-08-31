package com.agenttavern.game.card;

import static java.util.Objects.requireNonNull;

import java.util.List;

public record DeckCheckpoint(List<Card> remainingCards) {

    public DeckCheckpoint {
        requireNonNull(remainingCards, "remainingCards");
        remainingCards = List.copyOf(remainingCards);
    }
}
