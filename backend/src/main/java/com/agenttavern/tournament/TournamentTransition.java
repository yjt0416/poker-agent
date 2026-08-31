package com.agenttavern.tournament;

import static java.util.Objects.requireNonNull;

import java.util.List;

public record TournamentTransition(Tournament tournament, List<TournamentEvent> events) {

    public TournamentTransition {
        requireNonNull(tournament, "tournament");
        requireNonNull(events, "events");
        events = List.copyOf(events);
    }
}
