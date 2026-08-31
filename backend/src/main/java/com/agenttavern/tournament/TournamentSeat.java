package com.agenttavern.tournament;

import static java.util.Objects.requireNonNull;

import com.agenttavern.game.betting.PlayerId;

public record TournamentSeat(
        PlayerId playerId,
        int seatIndex,
        long stack,
        TournamentSeatStatus status,
        Integer finishPosition) {

    public TournamentSeat {
        requireNonNull(playerId, "playerId");
        requireNonNull(status, "status");
        if (seatIndex < 0 || seatIndex > 5) {
            throw new IllegalArgumentException("seat index must be between 0 and 5");
        }
        if (stack < 0) {
            throw new IllegalArgumentException("stack must be non-negative");
        }
        switch (status) {
            case FUNDED -> {
                if (finishPosition != null) {
                    throw new IllegalArgumentException("funded seat cannot have a finish position");
                }
            }
            case ELIMINATED -> {
                if (stack != 0 || finishPosition == null || finishPosition <= 1) {
                    throw new IllegalArgumentException(
                            "eliminated seat must have zero chips and a non-winning finish position");
                }
            }
            case WINNER -> {
                if (stack <= 0 || !Integer.valueOf(1).equals(finishPosition)) {
                    throw new IllegalArgumentException(
                            "winner must have chips and finish in first position");
                }
            }
        }
    }

    TournamentSeat withStack(long updatedStack) {
        return new TournamentSeat(playerId, seatIndex, updatedStack, status, finishPosition);
    }

    TournamentSeat eliminated(int position) {
        return new TournamentSeat(playerId, seatIndex, 0, TournamentSeatStatus.ELIMINATED, position);
    }

    TournamentSeat winner() {
        return new TournamentSeat(playerId, seatIndex, stack, TournamentSeatStatus.WINNER, 1);
    }
}
