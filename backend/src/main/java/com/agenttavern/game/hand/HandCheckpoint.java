package com.agenttavern.game.hand;

import static java.util.Objects.requireNonNull;

import com.agenttavern.game.betting.BettingRoundCheckpoint;
import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.betting.SeatState;
import com.agenttavern.game.betting.Street;
import com.agenttavern.game.card.Card;
import com.agenttavern.game.card.DeckCheckpoint;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record HandCheckpoint(
        HandId id,
        int buttonSeat,
        BlindLevel blinds,
        DeckCheckpoint deck,
        List<SeatState> seats,
        Map<PlayerId, List<Card>> holeCards,
        List<Card> board,
        List<Card> burnedCards,
        Optional<BettingRoundCheckpoint> bettingRound,
        Street street,
        boolean complete,
        long initialTotalChips) {

    public HandCheckpoint {
        requireNonNull(id, "id");
        requireNonNull(blinds, "blinds");
        requireNonNull(deck, "deck");
        requireNonNull(seats, "seats");
        requireNonNull(holeCards, "holeCards");
        requireNonNull(board, "board");
        requireNonNull(burnedCards, "burnedCards");
        requireNonNull(bettingRound, "bettingRound");
        requireNonNull(street, "street");
        seats = List.copyOf(seats);
        holeCards = immutableHoleCards(holeCards);
        board = List.copyOf(board);
        burnedCards = List.copyOf(burnedCards);
    }

    private static Map<PlayerId, List<Card>> immutableHoleCards(
            Map<PlayerId, List<Card>> source) {
        Map<PlayerId, List<Card>> copy = new LinkedHashMap<>();
        source.forEach((playerId, cards) ->
                copy.put(requireNonNull(playerId, "playerId"), List.copyOf(cards)));
        return Collections.unmodifiableMap(copy);
    }
}
