package com.agenttavern.tablechat;

import static java.util.Objects.requireNonNull;

import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.tournament.TournamentId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Applies server-authoritative chat validation and per-speaker cooldown. */
public final class TableChatService {
    public static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(5);

    private final Clock clock;
    private final Duration cooldown;
    private final ConcurrentMap<SpeakerAtTable, Instant> lastAccepted = new ConcurrentHashMap<>();

    public TableChatService(Clock clock) { this(clock, DEFAULT_COOLDOWN); }

    public TableChatService(Clock clock, Duration cooldown) {
        this.clock = requireNonNull(clock, "clock");
        this.cooldown = requireNonNull(cooldown, "cooldown");
        if (cooldown.isNegative()) throw new IllegalArgumentException("cooldown must not be negative");
    }

    public TableChatMessage submit(TournamentId tournamentId, PlayerId speakerId, String input) {
        requireNonNull(tournamentId, "tournamentId");
        requireNonNull(speakerId, "speakerId");
        String text = TableChatPolicy.normalize(input);
        Instant now = clock.instant();
        SpeakerAtTable key = new SpeakerAtTable(tournamentId, speakerId);
        lastAccepted.compute(key, (ignored, previous) -> {
            if (previous != null && now.isBefore(previous.plus(cooldown))) {
                throw new ChatRejectedException("message cooldown has not elapsed");
            }
            return now;
        });
        return new TableChatMessage(UUID.randomUUID(), tournamentId, speakerId, text, now);
    }

    private record SpeakerAtTable(TournamentId tournamentId, PlayerId speakerId) {}
}
