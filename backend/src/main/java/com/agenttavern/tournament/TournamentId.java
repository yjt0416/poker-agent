package com.agenttavern.tournament;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

public record TournamentId(UUID value) {

    public TournamentId {
        requireNonNull(value, "value");
    }

    public static TournamentId random() {
        return new TournamentId(UUID.randomUUID());
    }
}
