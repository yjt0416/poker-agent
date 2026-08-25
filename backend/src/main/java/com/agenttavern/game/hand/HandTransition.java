package com.agenttavern.game.hand;

import static java.util.Objects.requireNonNull;

import java.util.List;

public record HandTransition(Hand hand, List<HandEvent> events) {

    public HandTransition {
        requireNonNull(hand, "hand");
        requireNonNull(events, "events");
        events = List.copyOf(events);
    }
}
