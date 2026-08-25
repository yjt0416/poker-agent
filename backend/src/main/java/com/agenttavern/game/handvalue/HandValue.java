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
        tieBreakers = List.copyOf(tieBreakers);
    }

    @Override
    public int compareTo(HandValue other) {
        Objects.requireNonNull(other, "other");
        int categoryComparison = Integer.compare(category.ordinal(), other.category.ordinal());
        if (categoryComparison != 0) {
            return categoryComparison;
        }
        if (tieBreakers.size() != other.tieBreakers.size()) {
            throw new IllegalArgumentException(
                    "Cannot compare hand values with different tie-breaker lengths");
        }
        for (int index = 0; index < tieBreakers.size(); index++) {
            int comparison = Integer.compare(tieBreakers.get(index), other.tieBreakers.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }
}
