package com.agenttavern.game.hand;

import static java.util.Objects.requireNonNull;

import com.agenttavern.game.betting.PlayerAction;
import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.betting.SeatState;
import com.agenttavern.game.betting.Street;
import com.agenttavern.game.card.Card;
import com.agenttavern.game.showdown.Pot;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public sealed interface HandEvent
        permits HandEvent.HandStarted,
                HandEvent.HoleCardsDealt,
                HandEvent.BlindsPosted,
                HandEvent.PlayerActed,
                HandEvent.CommunityCardsDealt,
                HandEvent.PotsAwarded,
                HandEvent.HandCompleted {

    HandId handId();

    record HandStarted(
            HandId handId,
            List<PlayerStack> players,
            int buttonSeat,
            BlindLevel blinds) implements HandEvent {

        public HandStarted {
            requireNonNull(handId, "handId");
            requireNonNull(players, "players");
            requireNonNull(blinds, "blinds");
            players = List.copyOf(players);
            if (players.size() < 2 || players.size() > 6) {
                throw new IllegalArgumentException("started hand must contain two to six players");
            }
            Set<PlayerId> playerIds = new HashSet<>();
            Set<Integer> seatIndexes = new HashSet<>();
            for (PlayerStack player : players) {
                if (!playerIds.add(player.playerId()) || !seatIndexes.add(player.seatIndex())) {
                    throw new IllegalArgumentException("started players and seats must be unique");
                }
            }
            if (!seatIndexes.contains(buttonSeat)) {
                throw new IllegalArgumentException("button seat must be occupied");
            }
        }
    }

    record HoleCardsDealt(HandId handId, Map<PlayerId, List<Card>> holeCards)
            implements HandEvent {

        public HoleCardsDealt {
            requireNonNull(handId, "handId");
            holeCards = immutableHoleCards(holeCards);
            if (holeCards.size() < 2 || holeCards.size() > 6) {
                throw new IllegalArgumentException("hole cards must contain two to six players");
            }
            Set<Card> physicalCards = new HashSet<>();
            for (List<Card> cards : holeCards.values()) {
                if (cards.size() != 2) {
                    throw new IllegalArgumentException("each player must have exactly two hole cards");
                }
                for (Card card : cards) {
                    if (!physicalCards.add(card)) {
                        throw new IllegalArgumentException("hole cards must be physically unique");
                    }
                }
            }
        }
    }

    record BlindsPosted(
            HandId handId,
            PlayerId smallBlindPlayerId,
            int smallBlindSeat,
            long smallBlindAmount,
            PlayerId bigBlindPlayerId,
            int bigBlindSeat,
            long bigBlindAmount) implements HandEvent {

        public BlindsPosted {
            requireNonNull(handId, "handId");
            requireNonNull(smallBlindPlayerId, "smallBlindPlayerId");
            requireNonNull(bigBlindPlayerId, "bigBlindPlayerId");
            validateSeatIndex(smallBlindSeat);
            validateSeatIndex(bigBlindSeat);
            if (smallBlindPlayerId.equals(bigBlindPlayerId) || smallBlindSeat == bigBlindSeat) {
                throw new IllegalArgumentException("blind players and seats must be distinct");
            }
            if (smallBlindAmount <= 0 || bigBlindAmount <= 0) {
                throw new IllegalArgumentException("posted blind amounts must be positive");
            }
        }
    }

    record PlayerActed(
            HandId handId,
            PlayerId playerId,
            int seatIndex,
            Street street,
            PlayerAction action) implements HandEvent {

        public PlayerActed {
            requireNonNull(handId, "handId");
            requireNonNull(playerId, "playerId");
            requireNonNull(street, "street");
            requireNonNull(action, "action");
            validateSeatIndex(seatIndex);
            if (street == Street.SHOWDOWN) {
                throw new IllegalArgumentException("players cannot act at showdown");
            }
        }
    }

    record CommunityCardsDealt(HandId handId, Street street, List<Card> cards)
            implements HandEvent {

        public CommunityCardsDealt {
            requireNonNull(handId, "handId");
            requireNonNull(street, "street");
            requireNonNull(cards, "cards");
            cards = List.copyOf(cards);
            int expectedCount;
            if (street == Street.FLOP) {
                expectedCount = 3;
            } else if (street == Street.TURN || street == Street.RIVER) {
                expectedCount = 1;
            } else {
                throw new IllegalArgumentException("community cards require flop, turn, or river");
            }
            if (cards.size() != expectedCount) {
                throw new IllegalArgumentException("community-card count does not match street");
            }
            if (new HashSet<>(cards).size() != cards.size()) {
                throw new IllegalArgumentException("community cards must be physically unique");
            }
        }
    }

    record PotsAwarded(HandId handId, List<Pot> pots, Map<PlayerId, Long> payouts)
            implements HandEvent {

        public PotsAwarded {
            requireNonNull(handId, "handId");
            requireNonNull(pots, "pots");
            requireNonNull(payouts, "payouts");
            pots = List.copyOf(pots);
            Map<PlayerId, Long> payoutCopy = new LinkedHashMap<>();
            payouts.forEach((playerId, amount) -> payoutCopy.put(
                    requireNonNull(playerId, "payout playerId"),
                    requireNonNull(amount, "payout amount")));
            payouts = Collections.unmodifiableMap(payoutCopy);
            if (pots.isEmpty() || payouts.isEmpty()) {
                throw new IllegalArgumentException("awarded pots and payouts must not be empty");
            }
            Set<PlayerId> eligiblePlayers = new HashSet<>();
            long totalPots = 0;
            for (Pot pot : pots) {
                eligiblePlayers.addAll(pot.eligiblePlayers());
                totalPots = Math.addExact(totalPots, pot.amount());
            }
            long totalPayouts = 0;
            for (Map.Entry<PlayerId, Long> payout : payouts.entrySet()) {
                if (payout.getValue() <= 0) {
                    throw new IllegalArgumentException("payout amounts must be positive");
                }
                if (!eligiblePlayers.contains(payout.getKey())) {
                    throw new IllegalArgumentException("paid player must be eligible for a pot");
                }
                totalPayouts = Math.addExact(totalPayouts, payout.getValue());
            }
            if (totalPayouts != totalPots) {
                throw new IllegalArgumentException("payouts must equal awarded pots");
            }
        }
    }

    record HandCompleted(HandId handId, List<SeatState> seats) implements HandEvent {

        public HandCompleted {
            requireNonNull(handId, "handId");
            requireNonNull(seats, "seats");
            seats = List.copyOf(seats);
            if (seats.size() < 2 || seats.size() > 6) {
                throw new IllegalArgumentException("completed hand must contain two to six seats");
            }
            Set<PlayerId> playerIds = new HashSet<>();
            Set<Integer> seatIndexes = new HashSet<>();
            for (SeatState seat : seats) {
                if (!playerIds.add(seat.playerId()) || !seatIndexes.add(seat.seatIndex())) {
                    throw new IllegalArgumentException("completed players and seats must be unique");
                }
                if (seat.streetCommitted() != 0 || seat.handCommitted() != 0) {
                    throw new IllegalArgumentException("completed seats cannot retain contributions");
                }
            }
        }
    }

    private static Map<PlayerId, List<Card>> immutableHoleCards(
            Map<PlayerId, List<Card>> source) {
        requireNonNull(source, "holeCards");
        Map<PlayerId, List<Card>> copy = new LinkedHashMap<>();
        source.forEach((playerId, cards) ->
                copy.put(requireNonNull(playerId, "playerId"), List.copyOf(cards)));
        return Collections.unmodifiableMap(copy);
    }

    private static void validateSeatIndex(int seatIndex) {
        if (seatIndex < 0 || seatIndex > 5) {
            throw new IllegalArgumentException("seat index must be between 0 and 5");
        }
    }
}
