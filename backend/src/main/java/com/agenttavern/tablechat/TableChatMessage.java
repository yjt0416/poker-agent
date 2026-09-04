package com.agenttavern.tablechat;

import static java.util.Objects.requireNonNull;

import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.tournament.TournamentId;
import java.time.Instant;
import java.util.UUID;

public record TableChatMessage(
        UUID id, TournamentId tournamentId, PlayerId speakerId, String text, Instant occurredAt) {
    public TableChatMessage {
        requireNonNull(id, "id");
        requireNonNull(tournamentId, "tournamentId");
        requireNonNull(speakerId, "speakerId");
        requireNonNull(text, "text");
        requireNonNull(occurredAt, "occurredAt");
    }
}
