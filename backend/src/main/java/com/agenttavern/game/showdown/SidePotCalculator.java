package com.agenttavern.game.showdown;

import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.betting.PlayerStatus;
import com.agenttavern.game.betting.SeatState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class SidePotCalculator {

    private SidePotCalculator() {}

    public static List<Pot> calculate(List<SeatState> seats) {
        List<SeatState> validatedSeats = validateAndCopy(seats);
        Set<Long> contributionLevels = new TreeSet<>();
        long totalContributions = 0;
        for (SeatState seat : validatedSeats) {
            totalContributions = Math.addExact(totalContributions, seat.handCommitted());
            if (seat.handCommitted() > 0) {
                contributionLevels.add(seat.handCommitted());
            }
        }

        List<Pot> pots = new ArrayList<>(contributionLevels.size());
        long previousLevel = 0;
        long totalPots = 0;
        for (long level : contributionLevels) {
            long contributorCount = validatedSeats.stream()
                    .filter(seat -> seat.handCommitted() >= level)
                    .count();
            long layerWidth = Math.subtractExact(level, previousLevel);
            long amount = Math.multiplyExact(layerWidth, contributorCount);
            Set<PlayerId> eligiblePlayers = new LinkedHashSet<>();
            validatedSeats.stream()
                    .filter(seat -> seat.handCommitted() >= level)
                    .filter(SidePotCalculator::isEligible)
                    .map(SeatState::playerId)
                    .forEach(eligiblePlayers::add);
            if (eligiblePlayers.isEmpty()) {
                throw new IllegalArgumentException(
                        "non-zero contribution layer must have an eligible player");
            }
            pots.add(new Pot(amount, eligiblePlayers));
            totalPots = Math.addExact(totalPots, amount);
            previousLevel = level;
        }

        if (totalPots != totalContributions) {
            throw new IllegalStateException("pot total must equal total hand contributions");
        }
        return List.copyOf(pots);
    }

    private static List<SeatState> validateAndCopy(List<SeatState> seats) {
        if (seats == null) {
            throw new IllegalArgumentException("seats must not be null");
        }
        Set<PlayerId> playerIds = new HashSet<>();
        Set<Integer> seatIndexes = new HashSet<>();
        for (SeatState seat : seats) {
            if (seat == null) {
                throw new IllegalArgumentException("seats must not contain null");
            }
            if (!playerIds.add(seat.playerId())) {
                throw new IllegalArgumentException("seats must not contain duplicate players");
            }
            if (!seatIndexes.add(seat.seatIndex())) {
                throw new IllegalArgumentException("seats must not contain duplicate seat indexes");
            }
        }
        return List.copyOf(seats);
    }

    private static boolean isEligible(SeatState seat) {
        return seat.status() != PlayerStatus.FOLDED && seat.status() != PlayerStatus.OUT;
    }
}
