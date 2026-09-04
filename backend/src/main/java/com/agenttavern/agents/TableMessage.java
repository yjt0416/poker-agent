package com.agenttavern.agents;

import static java.util.Objects.requireNonNull;

import com.agenttavern.game.betting.PlayerId;

public record TableMessage(PlayerId speakerId, String text) {
    public TableMessage {
        requireNonNull(speakerId, "speakerId");
        requireNonNull(text, "text");
    }
}
