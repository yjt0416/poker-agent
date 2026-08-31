package com.agenttavern.tournament;

import static java.util.Objects.requireNonNull;

import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.hand.HandCheckpoint;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Complete, deterministic state required to resume a single-table tournament. */
public record TournamentCheckpoint(
        TournamentId id,
        TournamentMode mode,
        TournamentStatus status,
        List<TournamentSeat> seats,
        int buttonSeat,
        int completedHands,
        int blindLevelIndex,
        Optional<HandCheckpoint> currentHand,
        Map<PlayerId, Long> currentHandStartingStacks) {

    public TournamentCheckpoint {
        requireNonNull(id, "id");
        requireNonNull(mode, "mode");
        requireNonNull(status, "status");
        requireNonNull(seats, "seats");
        requireNonNull(currentHand, "currentHand");
        requireNonNull(currentHandStartingStacks, "currentHandStartingStacks");
        seats = List.copyOf(seats);
        currentHandStartingStacks = immutableStartingStacks(currentHandStartingStacks);
    }

    private static Map<PlayerId, Long> immutableStartingStacks(Map<PlayerId, Long> source) {
        Map<PlayerId, Long> copy = new LinkedHashMap<>();
        source.forEach((playerId, stack) -> copy.put(
                requireNonNull(playerId, "current hand player"),
                requireNonNull(stack, "current hand starting stack")));
        return Collections.unmodifiableMap(copy);
    }
}
