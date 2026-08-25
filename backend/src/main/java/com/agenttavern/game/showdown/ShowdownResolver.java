package com.agenttavern.game.showdown;

import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.card.Card;
import com.agenttavern.game.handvalue.HandEvaluator;
import com.agenttavern.game.handvalue.HandValue;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ShowdownResolver {

    private static final int TABLE_SIZE = 6;

    private ShowdownResolver() {}

    public static Map<PlayerId, Long> resolve(
            List<Pot> pots,
            Map<PlayerId, List<Card>> holeCards,
            List<Card> board,
            Map<PlayerId, Integer> seats,
            int buttonSeat) {
        List<Pot> validatedPots = validatePots(pots);
        Set<PlayerId> eligiblePlayers = eligiblePlayers(validatedPots);
        Map<PlayerId, List<Card>> validatedHoleCards =
                validateAndCopyInputs(eligiblePlayers, holeCards, board, seats, buttonSeat);
        List<Card> validatedBoard = List.copyOf(board);
        Map<PlayerId, Integer> validatedSeats = Map.copyOf(seats);

        long totalPots = validatedPots.stream()
                .mapToLong(Pot::amount)
                .reduce(0, Math::addExact);
        Map<PlayerId, HandValue> handValues = evaluateHands(
                eligiblePlayers, validatedHoleCards, validatedBoard);
        Map<PlayerId, Long> payouts = new LinkedHashMap<>();

        for (Pot pot : validatedPots) {
            List<PlayerId> winners = winners(pot, handValues);
            long equalShare = pot.amount() / winners.size();
            long remainder = pot.amount() % winners.size();
            if (equalShare > 0) {
                for (PlayerId winner : winners) {
                    addPayout(payouts, winner, equalShare);
                }
            }

            winners.sort(Comparator.comparingInt(
                    winner -> clockwiseDistance(validatedSeats.get(winner), buttonSeat)));
            for (int index = 0; index < remainder; index++) {
                addPayout(payouts, winners.get(index), 1);
            }
        }

        long totalPayouts = payouts.values().stream().mapToLong(Long::longValue)
                .reduce(0, Math::addExact);
        if (totalPayouts != totalPots) {
            throw new IllegalStateException("total payouts must equal total pots");
        }
        return Map.copyOf(payouts);
    }

    private static List<Pot> validatePots(List<Pot> pots) {
        if (pots == null) {
            throw new IllegalArgumentException("pots must not be null");
        }
        if (pots.stream().anyMatch(pot -> pot == null)) {
            throw new IllegalArgumentException("pots must not contain null");
        }
        return List.copyOf(pots);
    }

    private static Set<PlayerId> eligiblePlayers(List<Pot> pots) {
        Set<PlayerId> eligiblePlayers = new HashSet<>();
        pots.stream().map(Pot::eligiblePlayers).forEach(eligiblePlayers::addAll);
        return Set.copyOf(eligiblePlayers);
    }

    private static Map<PlayerId, List<Card>> validateAndCopyInputs(
            Set<PlayerId> eligiblePlayers,
            Map<PlayerId, List<Card>> holeCards,
            List<Card> board,
            Map<PlayerId, Integer> seats,
            int buttonSeat) {
        if (holeCards == null) {
            throw new IllegalArgumentException("holeCards must not be null");
        }
        if (board == null) {
            throw new IllegalArgumentException("board must not be null");
        }
        if (seats == null) {
            throw new IllegalArgumentException("seats must not be null");
        }
        if (buttonSeat < 0 || buttonSeat >= TABLE_SIZE) {
            throw new IllegalArgumentException("buttonSeat must be between 0 and 5");
        }
        if (board.size() != 5) {
            throw new IllegalArgumentException("board must contain exactly five cards");
        }
        if (!holeCards.keySet().containsAll(eligiblePlayers)
                || !seats.keySet().containsAll(eligiblePlayers)) {
            throw new IllegalArgumentException("an eligible player mapping is missing");
        }
        if (!holeCards.keySet().equals(seats.keySet())) {
            throw new IllegalArgumentException(
                    "hole-card and seat mappings must contain the same players");
        }
        if (holeCards.keySet().stream().anyMatch(playerId -> playerId == null)
                || seats.keySet().stream().anyMatch(playerId -> playerId == null)) {
            throw new IllegalArgumentException("player mappings must not contain null keys");
        }

        validateSeats(seats);
        validateCards(holeCards, board);

        Map<PlayerId, List<Card>> copiedHoleCards = new LinkedHashMap<>();
        holeCards.forEach((playerId, cards) -> copiedHoleCards.put(playerId, List.copyOf(cards)));
        return Map.copyOf(copiedHoleCards);
    }

    private static void validateSeats(Map<PlayerId, Integer> seats) {
        Set<Integer> occupiedSeats = new HashSet<>();
        for (Integer seat : seats.values()) {
            if (seat == null) {
                throw new IllegalArgumentException("seat mappings must not contain null");
            }
            if (seat < 0 || seat >= TABLE_SIZE) {
                throw new IllegalArgumentException("seat index must be between 0 and 5");
            }
            if (!occupiedSeats.add(seat)) {
                throw new IllegalArgumentException("player mappings must not contain duplicate seats");
            }
        }
    }

    private static void validateCards(
            Map<PlayerId, List<Card>> holeCards, List<Card> board) {
        Set<Card> seenCards = new HashSet<>();
        for (Card card : board) {
            addCard(seenCards, card);
        }
        for (List<Card> playerCards : holeCards.values()) {
            if (playerCards == null || playerCards.size() != 2) {
                throw new IllegalArgumentException("each player must have exactly two hole cards");
            }
            for (Card card : playerCards) {
                addCard(seenCards, card);
            }
        }
    }

    private static void addCard(Set<Card> seenCards, Card card) {
        if (card == null) {
            throw new IllegalArgumentException("cards must not contain null");
        }
        if (!seenCards.add(card)) {
            throw new IllegalArgumentException("cards must not contain duplicates");
        }
    }

    private static Map<PlayerId, HandValue> evaluateHands(
            Set<PlayerId> eligiblePlayers,
            Map<PlayerId, List<Card>> holeCards,
            List<Card> board) {
        Map<PlayerId, HandValue> handValues = new LinkedHashMap<>();
        for (PlayerId player : eligiblePlayers) {
            List<Card> sevenCards = new ArrayList<>(7);
            sevenCards.addAll(board);
            sevenCards.addAll(holeCards.get(player));
            handValues.put(player, HandEvaluator.evaluate(sevenCards));
        }
        return handValues;
    }

    private static List<PlayerId> winners(Pot pot, Map<PlayerId, HandValue> handValues) {
        HandValue best = null;
        List<PlayerId> winners = new ArrayList<>();
        for (PlayerId player : pot.eligiblePlayers()) {
            HandValue handValue = handValues.get(player);
            int comparison = best == null ? 1 : handValue.compareTo(best);
            if (comparison > 0) {
                best = handValue;
                winners.clear();
                winners.add(player);
            } else if (comparison == 0) {
                winners.add(player);
            }
        }
        return winners;
    }

    private static int clockwiseDistance(int seat, int buttonSeat) {
        return Math.floorMod(seat - buttonSeat - 1, TABLE_SIZE);
    }

    private static void addPayout(
            Map<PlayerId, Long> payouts, PlayerId player, long amount) {
        payouts.merge(player, amount, Math::addExact);
    }
}
