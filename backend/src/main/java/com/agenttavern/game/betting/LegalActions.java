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
        if (types.contains(ActionType.CALL) != (callAmount > 0)) {
            throw new IllegalArgumentException(
                    "CALL must be present exactly when callAmount is positive");
        }
        if (types.contains(ActionType.RAISE) != minRaiseTo.isPresent()) {
            throw new IllegalArgumentException(
                    "RAISE must be present exactly when minRaiseTo is present");
        }
        if (minRaiseTo.isPresent() && minRaiseTo.getAsLong() > maxRaiseTo) {
            throw new IllegalArgumentException("minRaiseTo cannot exceed maxRaiseTo");
        }
        if (callAmount > maxRaiseTo) {
            throw new IllegalArgumentException("callAmount cannot exceed maxRaiseTo");
        }
        if (types.contains(ActionType.ALL_IN) && maxRaiseTo == 0) {
            throw new IllegalArgumentException("ALL_IN requires a positive maxRaiseTo target");
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

        if (actor.status() != PlayerStatus.ACTIVE) {
            return new LegalActions(Set.of(), 0, OptionalLong.empty(), 0);
        }
        if (currentBet < actor.streetCommitted()) {
            throw new IllegalArgumentException(
                    "currentBet cannot be below actor.streetCommitted");
        }

        long maxRaiseTo = Math.addExact(actor.streetCommitted(), actor.stack());
        EnumSet<ActionType> types = EnumSet.noneOf(ActionType.class);
        long needed = currentBet > actor.streetCommitted()
                ? currentBet - actor.streetCommitted()
                : 0;
        long callAmount = 0;
        if (needed == 0) {
            types.add(ActionType.CHECK);
        } else {
            types.add(ActionType.FOLD);
            callAmount = Math.min(needed, actor.stack());
            if (callAmount > 0) {
                types.add(ActionType.CALL);
            }
        }
        if (actor.stack() > 0) {
            types.add(ActionType.ALL_IN);
        }
        OptionalLong minRaiseTo = OptionalLong.empty();
        OptionalLong minimum = fullRaiseMinimum(currentBet, lastFullRaiseSize);
        if (raiseRightsOpen && minimum.isPresent() && maxRaiseTo >= minimum.getAsLong()) {
            types.add(ActionType.RAISE);
            minRaiseTo = minimum;
        }
        return new LegalActions(types, callAmount, minRaiseTo, maxRaiseTo);
    }

    private static OptionalLong fullRaiseMinimum(long currentBet, long lastFullRaiseSize) {
        if (currentBet == 0) {
            return OptionalLong.of(lastFullRaiseSize);
        }
        if (currentBet > Long.MAX_VALUE - lastFullRaiseSize) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(currentBet + lastFullRaiseSize);
    }
}
