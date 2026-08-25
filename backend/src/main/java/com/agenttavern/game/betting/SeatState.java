package com.agenttavern.game.betting;

import static java.util.Objects.requireNonNull;

public record SeatState(
        PlayerId playerId,
        int seatIndex,
        long stack,
        long streetCommitted,
        long handCommitted,
        PlayerStatus status) {

    public SeatState {
        requireNonNull(playerId, "playerId");
        requireNonNull(status, "status");
        if (seatIndex < 0 || seatIndex > 5) {
            throw new IllegalArgumentException("seatIndex must be between 0 and 5");
        }
        if (stack < 0 || streetCommitted < 0 || handCommitted < 0) {
            throw new IllegalArgumentException("chip values must be non-negative");
        }
        if (streetCommitted > handCommitted) {
            throw new IllegalArgumentException("streetCommitted cannot exceed handCommitted");
        }
        if ((status == PlayerStatus.ALL_IN || status == PlayerStatus.OUT) && stack != 0) {
            throw new IllegalArgumentException("all-in and out seats must have zero stack");
        }
    }

    public SeatState withContribution(long chips) {
        if (status != PlayerStatus.ACTIVE) {
            throw new IllegalStateException(
                    "withContribution requires ACTIVE status, was " + status);
        }
        if (chips < 0) {
            throw new IllegalArgumentException("contribution must be non-negative");
        }
        if (chips > stack) {
            throw new IllegalArgumentException("contribution cannot exceed stack");
        }
        long nextStreetCommitted = Math.addExact(streetCommitted, chips);
        long nextHandCommitted = Math.addExact(handCommitted, chips);
        long nextStack = stack - chips;
        PlayerStatus nextStatus = chips > 0 && nextStack == 0 ? PlayerStatus.ALL_IN : status;
        return new SeatState(
                playerId,
                seatIndex,
                nextStack,
                nextStreetCommitted,
                nextHandCommitted,
                nextStatus);
    }

    public SeatState fold() {
        if (status != PlayerStatus.ACTIVE) {
            throw new IllegalStateException("fold requires ACTIVE status, was " + status);
        }
        return new SeatState(
                playerId, seatIndex, stack, streetCommitted, handCommitted, PlayerStatus.FOLDED);
    }

    public SeatState resetStreet() {
        return new SeatState(playerId, seatIndex, stack, 0, handCommitted, status);
    }

    public SeatState settled(long payout) {
        if (payout < 0) {
            throw new IllegalArgumentException("payout must be non-negative");
        }
        long settledStack = Math.addExact(stack, payout);
        PlayerStatus settledStatus = settledStack > 0 ? PlayerStatus.ACTIVE : PlayerStatus.OUT;
        return new SeatState(playerId, seatIndex, settledStack, 0, 0, settledStatus);
    }
}
