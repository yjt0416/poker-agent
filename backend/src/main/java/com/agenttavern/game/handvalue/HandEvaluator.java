package com.agenttavern.game.handvalue;

import com.agenttavern.game.card.Card;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.IntStream;

public final class HandEvaluator {

    private HandEvaluator() {}

    public static HandValue evaluate(List<Card> cards) {
        validate(cards);

        HandValue best = null;
        int size = cards.size();
        for (int first = 0; first < size - 4; first++) {
            for (int second = first + 1; second < size - 3; second++) {
                for (int third = second + 1; third < size - 2; third++) {
                    for (int fourth = third + 1; fourth < size - 1; fourth++) {
                        for (int fifth = fourth + 1; fifth < size; fifth++) {
                            HandValue candidate = classify(List.of(
                                    cards.get(first),
                                    cards.get(second),
                                    cards.get(third),
                                    cards.get(fourth),
                                    cards.get(fifth)));
                            if (best == null || candidate.compareTo(best) > 0) {
                                best = candidate;
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    private static HandValue classify(List<Card> cards) {
        Map<Integer, Integer> frequencies = new HashMap<>();
        for (Card card : cards) {
            frequencies.merge(card.rank().strength(), 1, Integer::sum);
        }

        boolean flush = cards.stream().map(Card::suit).distinct().count() == 1;
        Set<Integer> ranks = new HashSet<>(frequencies.keySet());
        OptionalInt straightHigh = straightHigh(ranks);
        List<Integer> descendingRanks = ranks.stream()
                .sorted(Comparator.reverseOrder())
                .toList();
        List<Integer> quads = ranksWithCount(frequencies, 4);
        List<Integer> trips = ranksWithCount(frequencies, 3);
        List<Integer> pairs = ranksWithCount(frequencies, 2);
        List<Integer> singles = ranksWithCount(frequencies, 1);

        if (straightHigh.isPresent() && flush) {
            return new HandValue(HandCategory.STRAIGHT_FLUSH, List.of(straightHigh.getAsInt()));
        }
        if (!quads.isEmpty()) {
            return new HandValue(HandCategory.FOUR_OF_A_KIND, joined(quads, singles));
        }
        if (!trips.isEmpty() && !pairs.isEmpty()) {
            return new HandValue(HandCategory.FULL_HOUSE, List.of(trips.getFirst(), pairs.getFirst()));
        }
        if (flush) {
            return new HandValue(HandCategory.FLUSH, descendingRanks);
        }
        if (straightHigh.isPresent()) {
            return new HandValue(HandCategory.STRAIGHT, List.of(straightHigh.getAsInt()));
        }
        if (!trips.isEmpty()) {
            return new HandValue(HandCategory.THREE_OF_A_KIND, joined(trips, singles));
        }
        if (pairs.size() == 2) {
            return new HandValue(HandCategory.TWO_PAIR, joined(pairs, singles));
        }
        if (pairs.size() == 1) {
            return new HandValue(HandCategory.ONE_PAIR, joined(pairs, singles));
        }
        return new HandValue(HandCategory.HIGH_CARD, descendingRanks);
    }

    private static void validate(List<Card> cards) {
        if (cards == null) {
            throw new IllegalArgumentException("cards must not be null");
        }
        if (cards.size() < 5 || cards.size() > 7) {
            throw new IllegalArgumentException("cards must contain between 5 and 7 cards");
        }
        if (cards.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("cards must not contain null");
        }
        if (new HashSet<>(cards).size() != cards.size()) {
            throw new IllegalArgumentException("cards must not contain duplicates");
        }
    }

    private static List<Integer> ranksWithCount(Map<Integer, Integer> frequencies, int count) {
        return frequencies.entrySet().stream()
                .filter(entry -> entry.getValue() == count)
                .map(Map.Entry::getKey)
                .sorted(Comparator.reverseOrder())
                .toList();
    }

    private static List<Integer> joined(List<Integer> first, List<Integer> second) {
        List<Integer> result = new ArrayList<>(first.size() + second.size());
        result.addAll(first);
        result.addAll(second);
        return result;
    }

    private static OptionalInt straightHigh(Set<Integer> ranks) {
        List<Integer> descending = ranks.stream().sorted(Comparator.reverseOrder()).toList();
        for (int high : descending) {
            if (IntStream.rangeClosed(high - 4, high).allMatch(ranks::contains)) {
                return OptionalInt.of(high);
            }
        }
        return ranks.containsAll(Set.of(14, 2, 3, 4, 5))
                ? OptionalInt.of(5)
                : OptionalInt.empty();
    }
}
