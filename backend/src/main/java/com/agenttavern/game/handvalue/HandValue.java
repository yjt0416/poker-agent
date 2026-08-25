package com.agenttavern.game.handvalue;

import java.util.List;
import java.util.Objects;

public record HandValue(HandCategory category, List<Integer> tieBreakers)
        implements Comparable<HandValue> {

    public HandValue {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(tieBreakers, "tieBreakers");
        if (tieBreakers.isEmpty()) {
            throw new IllegalArgumentException("tieBreakers must not be empty");
        }
        if (tieBreakers.stream().anyMatch(rank -> rank == null || rank < 2 || rank > 14)) {
            throw new IllegalArgumentException("tieBreakers must contain only ranks from 2 to 14");
        }
        int expectedSize = expectedTieBreakerCount(category);
        if (tieBreakers.size() != expectedSize) {
            throw new IllegalArgumentException(
                    category + " requires exactly " + expectedSize + " tie breakers");
        }
        if (tieBreakers.stream().distinct().count() != tieBreakers.size()) {
            throw new IllegalArgumentException("tieBreakers must contain distinct ranks");
        }
        validateOrder(category, tieBreakers);
        tieBreakers = List.copyOf(tieBreakers);
    }

    @Override
    public int compareTo(HandValue other) {
        Objects.requireNonNull(other, "other");
        int categoryComparison = Integer.compare(category.ordinal(), other.category.ordinal());
        if (categoryComparison != 0) {
            return categoryComparison;
        }
        for (int index = 0; index < tieBreakers.size(); index++) {
            int comparison = Integer.compare(tieBreakers.get(index), other.tieBreakers.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static int expectedTieBreakerCount(HandCategory category) {
        return switch (category) {
            case HIGH_CARD, FLUSH -> 5;
            case ONE_PAIR -> 4;
            case TWO_PAIR, THREE_OF_A_KIND -> 3;
            case STRAIGHT, STRAIGHT_FLUSH -> 1;
            case FULL_HOUSE, FOUR_OF_A_KIND -> 2;
        };
    }

    private static void validateOrder(HandCategory category, List<Integer> tieBreakers) {
        switch (category) {
            case HIGH_CARD, FLUSH -> {
                requireDescending(tieBreakers, 0);
                if (formsStraight(tieBreakers)) {
                    throw new IllegalArgumentException(
                            "high-card and flush ranks must not form a straight");
                }
            }
            case ONE_PAIR, THREE_OF_A_KIND -> requireDescending(tieBreakers, 1);
            case TWO_PAIR -> {
                if (tieBreakers.get(0) <= tieBreakers.get(1)) {
                    throw new IllegalArgumentException("two-pair ranks must be high pair then low pair");
                }
            }
            case STRAIGHT, STRAIGHT_FLUSH -> {
                if (tieBreakers.getFirst() < 5) {
                    throw new IllegalArgumentException("straight high rank must be between 5 and 14");
                }
            }
            case FULL_HOUSE, FOUR_OF_A_KIND -> {
                // The vector positions already identify the made ranks and kicker.
            }
        }
    }

    private static boolean formsStraight(List<Integer> descendingRanks) {
        boolean consecutive = true;
        for (int index = 1; index < descendingRanks.size(); index++) {
            if (descendingRanks.get(index - 1) != descendingRanks.get(index) + 1) {
                consecutive = false;
                break;
            }
        }
        return consecutive || descendingRanks.equals(List.of(14, 5, 4, 3, 2));
    }

    private static void requireDescending(List<Integer> tieBreakers, int startIndex) {
        for (int index = startIndex + 1; index < tieBreakers.size(); index++) {
            if (tieBreakers.get(index - 1) <= tieBreakers.get(index)) {
                throw new IllegalArgumentException("kicker ranks must be in descending order");
            }
        }
    }
}
