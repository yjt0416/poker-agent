package com.agenttavern.game.betting;

import java.util.EnumSet;
import java.util.OptionalLong;
import java.util.Set;

import static java.util.Objects.requireNonNull;

public record LegalActions(
        Set<ActionType> types,
        long callAmount,
        OptionalLong minRaiseTo,
        long maxRaiseTo) {

    public LegalActions {
        requireNonNull(types, "types");
        requireNonNull(minRaiseTo, "minRaiseTo");
        types = Set.copyOf(types);
        if (callAmount < 0 || maxRaiseTo < 0) {
            throw new IllegalArgumentException("action amounts must be non-negative");
        }
        if (minRaiseTo.isPresent() && minRaiseTo.getAsLong() <= 0) {
            throw new IllegalArgumentException("minimum raise target must be positive");
        }
    }

    public static LegalActions calculate(
            SeatState actor,
            long currentBet,
            long lastFullRaiseSize,
            boolean raiseRightsOpen) {
        requireNonNull(actor, "actor");
        if (currentBet < 0) {
            throw new IllegalArgumentException("currentBet must be non-negative");
        }
        if (lastFullRaiseSize <= 0) {
            throw new IllegalArgumentException("lastFullRaiseSize must be positive");
        }

        long maxRaiseTo = Math.addExact(actor.streetCommitted(), actor.stack());
        if (actor.status() != PlayerStatus.ACTIVE) {
            return new LegalActions(Set.of(), 0, OptionalLong.empty(), maxRaiseTo);
        }

        EnumSet<ActionType> types = EnumSet.noneOf(ActionType.class);
        long needed = currentBet > actor.streetCommitted()
                ? currentBet - actor.streetCommitted()
                : 0;
        long callAmount = 0;
        if (needed == 0) {
            types.add(ActionType.CHECK);
        } else {
            types.add(ActionType.FOLD);
            types.add(ActionType.CALL);
            callAmount = Math.min(needed, actor.stack());
        }
        if (actor.stack() > 0) {
            types.add(ActionType.ALL_IN);
        }
        long minimum = currentBet == 0
                ? lastFullRaiseSize
                : Math.addExact(currentBet, lastFullRaiseSize);
        OptionalLong minRaiseTo = OptionalLong.empty();
        if (raiseRightsOpen && maxRaiseTo >= minimum) {
            types.add(ActionType.RAISE);
            minRaiseTo = OptionalLong.of(minimum);
        }
        return new LegalActions(types, callAmount, minRaiseTo, maxRaiseTo);
    }
}
