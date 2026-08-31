package com.agenttavern.tournament;

import static java.util.Objects.requireNonNull;

import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.hand.BlindLevel;
import com.agenttavern.game.hand.HandEvent;
import java.util.List;

public sealed interface TournamentEvent
        permits TournamentEvent.TournamentStarted,
                TournamentEvent.HandEventRecorded,
                TournamentEvent.PlayerEliminated,
                TournamentEvent.BlindLevelAdvanced,
                TournamentEvent.TournamentCompleted {

    TournamentId tournamentId();

    record TournamentStarted(
            TournamentId tournamentId,
            TournamentMode mode,
            List<TournamentSeat> seats,
            int buttonSeat) implements TournamentEvent {

        public TournamentStarted {
            requireNonNull(tournamentId, "tournamentId");
            requireNonNull(mode, "mode");
            requireNonNull(seats, "seats");
            seats = List.copyOf(seats);
            if (seats.size() != 6) {
                throw new IllegalArgumentException("a tournament must start with six seats");
            }
            if (buttonSeat < 0 || buttonSeat > 5) {
                throw new IllegalArgumentException("button seat must be between 0 and 5");
            }
        }
    }

    record HandEventRecorded(TournamentId tournamentId, HandEvent handEvent)
            implements TournamentEvent {

        public HandEventRecorded {
            requireNonNull(tournamentId, "tournamentId");
            requireNonNull(handEvent, "handEvent");
        }
    }

    record PlayerEliminated(TournamentId tournamentId, PlayerId playerId, int finishPosition)
            implements TournamentEvent {

        public PlayerEliminated {
            requireNonNull(tournamentId, "tournamentId");
            requireNonNull(playerId, "playerId");
            if (finishPosition < 2 || finishPosition > 6) {
                throw new IllegalArgumentException("eliminated finish position must be between 2 and 6");
            }
        }
    }

    record BlindLevelAdvanced(TournamentId tournamentId, int levelIndex, BlindLevel blinds)
            implements TournamentEvent {

        public BlindLevelAdvanced {
            requireNonNull(tournamentId, "tournamentId");
            requireNonNull(blinds, "blinds");
            if (levelIndex < 1) {
                throw new IllegalArgumentException("advanced blind-level index must be positive");
            }
        }
    }

    record TournamentCompleted(
            TournamentId tournamentId,
            PlayerId winnerId,
            List<TournamentSeat> finalSeats) implements TournamentEvent {

        public TournamentCompleted {
            requireNonNull(tournamentId, "tournamentId");
            requireNonNull(winnerId, "winnerId");
            requireNonNull(finalSeats, "finalSeats");
            finalSeats = List.copyOf(finalSeats);
            if (finalSeats.size() != 6) {
                throw new IllegalArgumentException("completed tournament must contain six seats");
            }
        }
    }
}
