package com.agenttavern.tournament;

import static java.util.Objects.requireNonNull;

import com.agenttavern.game.hand.HandId;
import java.time.Instant;
import java.util.Optional;

/** A persisted tournament event together with its ordering and occurrence metadata. */
public record TournamentEventEnvelope(
        TournamentId tournamentId,
        Optional<HandId> handId,
        long sequence,
        long aggregateVersion,
        Instant occurredAt,
        TournamentEvent payload) {

    public TournamentEventEnvelope {
        requireNonNull(tournamentId, "tournamentId");
        requireNonNull(handId, "handId");
        requireNonNull(occurredAt, "occurredAt");
        requireNonNull(payload, "payload");
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        if (aggregateVersion <= 0) {
            throw new IllegalArgumentException("aggregate version must be positive");
        }
        if (!tournamentId.equals(payload.tournamentId())) {
            throw new IllegalArgumentException("envelope and payload tournament IDs must match");
        }

        if (payload instanceof TournamentEvent.HandEventRecorded recorded) {
            HandId recordedHandId = recorded.handEvent().handId();
            if (!handId.equals(Optional.of(recordedHandId))) {
                throw new IllegalArgumentException("hand event envelope must contain its hand ID");
            }
        } else if (handId.isPresent()) {
            throw new IllegalArgumentException("only recorded hand events may contain a hand ID");
        }
    }
}
