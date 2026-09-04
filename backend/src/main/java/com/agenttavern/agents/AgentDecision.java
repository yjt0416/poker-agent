package com.agenttavern.agents;

import static java.util.Objects.requireNonNull;

import com.agenttavern.game.betting.PlayerAction;
import java.util.List;

public record AgentDecision(
        PlayerAction action,
        String tableTalk,
        AgentEmotion emotion,
        String publicSummary,
        List<String> memoryUpdates) {

    public AgentDecision {
        requireNonNull(action, "action");
        requireNonNull(tableTalk, "tableTalk");
        requireNonNull(emotion, "emotion");
        requireNonNull(publicSummary, "publicSummary");
        memoryUpdates = List.copyOf(memoryUpdates);
        if (tableTalk.codePointCount(0, tableTalk.length()) > 120) throw new IllegalArgumentException("tableTalk too long");
        if (publicSummary.codePointCount(0, publicSummary.length()) > 160) throw new IllegalArgumentException("publicSummary too long");
        if (memoryUpdates.size() > 3 || memoryUpdates.stream().anyMatch(value -> value.length() > 240)) {
            throw new IllegalArgumentException("memory updates exceed budget");
        }
    }
}
