package com.agenttavern.game.hand;

import static com.agenttavern.game.betting.PlayerStatus.ACTIVE;
import static com.agenttavern.game.betting.Street.PREFLOP;
import static java.util.Objects.requireNonNull;

import com.agenttavern.game.betting.BettingRound;
import com.agenttavern.game.betting.IllegalActionException;
import com.agenttavern.game.betting.LegalActions;
import com.agenttavern.game.betting.PlayerAction;
import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.betting.SeatState;
import com.agenttavern.game.betting.Street;
import com.agenttavern.game.card.Card;
import com.agenttavern.game.card.Deck;
import com.agenttavern.game.showdown.Pot;
import com.agenttavern.game.showdown.ShowdownResolver;
import com.agenttavern.game.showdown.SidePotCalculator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

public final class Hand {

    private static final int TABLE_SIZE = 6;
    private static final int COMMUNITY_AND_BURN_CARDS = 8;
    private static final LegalActions NO_LEGAL_ACTIONS =
            new LegalActions(Set.of(), 0, OptionalLong.empty(), 0);

    private final HandId id;
    private final int buttonSeat;
    private final BlindLevel blinds;
    private final Deck deck;
    private final List<SeatState> seats;
    private final Map<PlayerId, List<Card>> holeCards;
    private final List<Card> board;
    private final List<Card> burnedCards;
    private final BettingRound bettingRound;
    private final Street street;
    private final boolean complete;
    private final long initialTotalChips;

    private Hand(
            HandId id,
            int buttonSeat,
            BlindLevel blinds,
            Deck deck,
            List<SeatState> seats,
            Map<PlayerId, List<Card>> holeCards,
            List<Card> board,
            List<Card> burnedCards,
            BettingRound bettingRound,
            Street street,
            boolean complete,
            long initialTotalChips) {
        this.id = requireNonNull(id, "id");
        this.buttonSeat = buttonSeat;
        this.blinds = requireNonNull(blinds, "blinds");
        this.deck = requireNonNull(deck, "deck");
        this.seats = List.copyOf(seats);
        this.holeCards = immutableHoleCards(holeCards);
        this.board = List.copyOf(board);
        this.burnedCards = List.copyOf(burnedCards);
        this.bettingRound = bettingRound;
        this.street = requireNonNull(street, "street");
        this.complete = complete;
        this.initialTotalChips = initialTotalChips;
    }

    public static HandTransition start(
            HandId id,
            List<PlayerStack> players,
            int buttonSeat,
            BlindLevel blinds,
            Deck deck) {
        requireNonNull(id, "id");
        requireNonNull(players, "players");
        requireNonNull(blinds, "blinds");
        requireNonNull(deck, "deck");
        List<PlayerStack> validatedPlayers = players.stream()
                .peek(player -> requireNonNull(player, "player"))
                .sorted(Comparator.comparingInt(PlayerStack::seatIndex))
                .toList();
        validateTable(validatedPlayers, buttonSeat, deck);
        long initialTotalChips = validatedPlayers.stream()
                .mapToLong(PlayerStack::chips)
                .reduce(0, Math::addExact);

        List<PlayerStack> dealOrder = clockwisePlayers(validatedPlayers, buttonSeat);
        Map<PlayerId, List<Card>> mutableHoleCards = new LinkedHashMap<>();
        dealOrder.forEach(player -> mutableHoleCards.put(player.playerId(), new ArrayList<>(2)));
        Deck remainingDeck = deck;
        for (int round = 0; round < 2; round++) {
            for (PlayerStack player : dealOrder) {
                Deck.Draw draw = remainingDeck.draw();
                mutableHoleCards.get(player.playerId()).add(draw.card());
                remainingDeck = draw.deck();
            }
        }
        Map<PlayerId, List<Card>> dealtHoleCards = immutableHoleCards(mutableHoleCards);

        List<SeatState> seats = validatedPlayers.stream()
                .map(player -> new SeatState(
                        player.playerId(), player.seatIndex(), player.chips(), 0, 0, ACTIVE))
                .toList();
        PlayerStack smallBlindPlayer = validatedPlayers.size() == 2
                ? playerAtSeat(validatedPlayers, buttonSeat)
                : nextPlayer(validatedPlayers, buttonSeat);
        PlayerStack bigBlindPlayer = nextPlayer(validatedPlayers, smallBlindPlayer.seatIndex());
        SeatState smallBlind = seat(seats, smallBlindPlayer.playerId());
        long smallBlindAmount = Math.min(blinds.smallBlind(), smallBlind.stack());
        seats = replace(seats, smallBlind.withContribution(smallBlindAmount));
        SeatState bigBlind = seat(seats, bigBlindPlayer.playerId());
        long bigBlindAmount = Math.min(blinds.bigBlind(), bigBlind.stack());
        seats = replace(seats, bigBlind.withContribution(bigBlindAmount));

        BettingRound bettingRound = null;
        if (preflopActionRequired(seats)) {
            int firstToActSeat = firstPreflopActorSeat(
                    seats, validatedPlayers.size(), buttonSeat, bigBlindPlayer.seatIndex());
            bettingRound = BettingRound.start(
                    seats, PREFLOP, firstToActSeat, blinds.bigBlind());
            seats = bettingRound.seats();
        }
        Hand hand = new Hand(
                id,
                buttonSeat,
                blinds,
                remainingDeck,
                seats,
                dealtHoleCards,
                List.of(),
                List.of(),
                bettingRound,
                PREFLOP,
                false,
                initialTotalChips);

        List<HandEvent> events = new ArrayList<>();
        events.add(new HandEvent.HandStarted(id, validatedPlayers, buttonSeat, blinds));
        events.add(new HandEvent.HoleCardsDealt(id, dealtHoleCards));
        events.add(new HandEvent.BlindsPosted(
                id,
                smallBlindPlayer.playerId(),
                smallBlindPlayer.seatIndex(),
                smallBlindAmount,
                bigBlindPlayer.playerId(),
                bigBlindPlayer.seatIndex(),
                bigBlindAmount));
        return bettingRound == null
                ? runoutAndSettle(hand, events)
                : new HandTransition(hand, events);
    }

    public SeatState actor() {
        return complete || bettingRound == null ? null : bettingRound.actor();
    }

    public LegalActions legalActions() {
        return actor() == null ? NO_LEGAL_ACTIONS : bettingRound.legalActions();
    }

    public HandTransition act(PlayerId playerId, PlayerAction action) {
        requireNonNull(playerId, "playerId");
        if (complete) {
            throw new IllegalStateException("completed hand does not accept actions");
        }
        SeatState expectedActor = actor();
        if (expectedActor == null || !expectedActor.playerId().equals(playerId)) {
            throw new IllegalActionException("player is not the current actor");
        }
        requireNonNull(action, "action");
        BettingRound updatedRound = bettingRound.apply(action);
        Hand updatedHand = new Hand(
                id,
                buttonSeat,
                blinds,
                deck,
                updatedRound.seats(),
                holeCards,
                board,
                burnedCards,
                updatedRound,
                street,
                false,
                initialTotalChips);
        List<HandEvent> events = new ArrayList<>();
        events.add(new HandEvent.PlayerActed(
                id, playerId, expectedActor.seatIndex(), street, action));

        if (nonFoldedCount(updatedHand.seats) == 1) {
            return settleUncontested(updatedHand, events);
        }
        if (!updatedRound.isComplete()) {
            return new HandTransition(updatedHand, events);
        }
        if (noFurtherBettingPossible(updatedHand.seats)) {
            return runoutAndSettle(updatedHand, events);
        }
        if (street == Street.RIVER) {
            return settleShowdown(updatedHand, events);
        }
        return advanceStreet(updatedHand, events);
    }

    public Street street() {
        return street;
    }

    public List<Card> board() {
        return board;
    }

    public List<SeatState> seats() {
        return seats;
    }

    public List<Card> holeCards(PlayerId playerId) {
        requireNonNull(playerId, "playerId");
        List<Card> cards = holeCards.get(playerId);
        if (cards == null) {
            throw new IllegalArgumentException("unknown player: " + playerId);
        }
        return cards;
    }

    public boolean isComplete() {
        return complete;
    }

    public HandCheckpoint checkpoint() {
        return new HandCheckpoint(
                id,
                buttonSeat,
                blinds,
                deck.checkpoint(),
                seats,
                holeCards,
                board,
                burnedCards,
                Optional.ofNullable(bettingRound).map(BettingRound::checkpoint),
                street,
                complete,
                initialTotalChips);
    }

    public static Hand restore(HandCheckpoint checkpoint) {
        requireNonNull(checkpoint, "checkpoint");
        Deck restoredDeck = Deck.restore(checkpoint.deck());
        BettingRound restoredRound = checkpoint.bettingRound()
                .map(BettingRound::restore)
                .orElse(null);
        validateCheckpoint(checkpoint, restoredDeck, restoredRound);
        return new Hand(
                checkpoint.id(),
                checkpoint.buttonSeat(),
                checkpoint.blinds(),
                restoredDeck,
                checkpoint.seats(),
                checkpoint.holeCards(),
                checkpoint.board(),
                checkpoint.burnedCards(),
                restoredRound,
                checkpoint.street(),
                checkpoint.complete(),
                checkpoint.initialTotalChips());
    }

    public long totalChipsInSystem() {
        long total = seats.stream()
                .mapToLong(seat -> Math.addExact(seat.stack(), seat.handCommitted()))
                .reduce(0, Math::addExact);
        if (total != initialTotalChips) {
            throw new IllegalStateException("hand must conserve its initial chips");
        }
        return total;
    }

    private static void validateTable(
            List<PlayerStack> players, int buttonSeat, Deck deck) {
        if (players.size() < 2 || players.size() > TABLE_SIZE) {
            throw new IllegalArgumentException("a hand requires two to six funded players");
        }
        Set<PlayerId> playerIds = new HashSet<>();
        Set<Integer> seatIndexes = new HashSet<>();
        for (PlayerStack player : players) {
            if (!playerIds.add(player.playerId())) {
                throw new IllegalArgumentException("player IDs must be unique");
            }
            if (!seatIndexes.add(player.seatIndex())) {
                throw new IllegalArgumentException("seat indexes must be unique");
            }
        }
        if (!seatIndexes.contains(buttonSeat)) {
            throw new IllegalArgumentException("button seat must be occupied");
        }
        int requiredCards = Math.addExact(Math.multiplyExact(players.size(), 2), COMMUNITY_AND_BURN_CARDS);
        if (deck.remaining() < requiredCards) {
            throw new IllegalArgumentException("deck does not contain enough cards for the hand");
        }
    }

    private static void validateCheckpoint(
            HandCheckpoint checkpoint, Deck restoredDeck, BettingRound restoredRound) {
        List<SeatState> checkpointSeats = checkpoint.seats();
        validateCheckpointSeats(checkpointSeats, checkpoint.buttonSeat());
        validateHoleCards(checkpoint.holeCards(), checkpointSeats);
        validateCardUniqueness(checkpoint, restoredDeck);
        validateStreetCards(checkpoint.street(), checkpoint.board(), checkpoint.burnedCards());
        validateChipConservation(checkpointSeats, checkpoint.initialTotalChips());

        if (checkpoint.complete()) {
            if (checkpoint.bettingRound().isPresent()) {
                throw new IllegalArgumentException("completed hand cannot retain a betting round");
            }
            for (SeatState seat : checkpointSeats) {
                if (seat.streetCommitted() != 0 || seat.handCommitted() != 0) {
                    throw new IllegalArgumentException("completed hand cannot retain committed chips");
                }
            }
            return;
        }
        if (checkpoint.street() == Street.SHOWDOWN) {
            throw new IllegalArgumentException("incomplete hand cannot be at showdown");
        }
        if (restoredRound == null || restoredRound.isComplete()) {
            throw new IllegalArgumentException("incomplete hand must retain an active betting round");
        }
        if (restoredRound.street() != checkpoint.street()) {
            throw new IllegalArgumentException("betting round street must match the hand street");
        }
        if (!restoredRound.seats().equals(checkpointSeats)) {
            throw new IllegalArgumentException("betting round seats must match hand seats");
        }
    }

    private static void validateCheckpointSeats(List<SeatState> seats, int buttonSeat) {
        if (seats.size() < 2 || seats.size() > TABLE_SIZE) {
            throw new IllegalArgumentException("a hand requires two to six seats");
        }
        Set<PlayerId> playerIds = new HashSet<>();
        Set<Integer> seatIndexes = new HashSet<>();
        for (SeatState seat : seats) {
            if (!playerIds.add(seat.playerId())) {
                throw new IllegalArgumentException("player IDs must be unique");
            }
            if (!seatIndexes.add(seat.seatIndex())) {
                throw new IllegalArgumentException("seat indexes must be unique");
            }
        }
        if (!seatIndexes.contains(buttonSeat)) {
            throw new IllegalArgumentException("button seat must be occupied");
        }
    }

    private static void validateHoleCards(
            Map<PlayerId, List<Card>> holeCards, List<SeatState> seats) {
        Set<PlayerId> seatIds = new HashSet<>();
        seats.forEach(seat -> seatIds.add(seat.playerId()));
        if (!holeCards.keySet().equals(seatIds)) {
            throw new IllegalArgumentException("hole cards must be assigned to every seat");
        }
        for (List<Card> cards : holeCards.values()) {
            if (cards.size() != 2) {
                throw new IllegalArgumentException("each seat must have exactly two hole cards");
            }
        }
    }

    private static void validateCardUniqueness(HandCheckpoint checkpoint, Deck restoredDeck) {
        Set<Card> physicalCards = new HashSet<>();
        addUniqueCards(physicalCards, restoredDeck.checkpoint().remainingCards());
        checkpoint.holeCards().values().forEach(cards -> addUniqueCards(physicalCards, cards));
        addUniqueCards(physicalCards, checkpoint.board());
        addUniqueCards(physicalCards, checkpoint.burnedCards());
    }

    private static void addUniqueCards(Set<Card> physicalCards, List<Card> cards) {
        for (Card card : cards) {
            if (!physicalCards.add(card)) {
                throw new IllegalArgumentException("duplicate physical card in hand checkpoint: " + card);
            }
        }
    }

    private static void validateStreetCards(
            Street street, List<Card> board, List<Card> burnedCards) {
        int expectedBoardSize = switch (street) {
            case PREFLOP -> 0;
            case FLOP -> 3;
            case TURN -> 4;
            case RIVER, SHOWDOWN -> 5;
        };
        int expectedBurnedSize = switch (street) {
            case PREFLOP -> 0;
            case FLOP -> 1;
            case TURN -> 2;
            case RIVER, SHOWDOWN -> 3;
        };
        if (board.size() != expectedBoardSize || burnedCards.size() != expectedBurnedSize) {
            throw new IllegalArgumentException("board and burned cards must match the hand street");
        }
    }

    private static void validateChipConservation(List<SeatState> seats, long initialTotalChips) {
        final long actualTotal;
        try {
            actualTotal = seats.stream()
                    .mapToLong(seat -> Math.addExact(seat.stack(), seat.handCommitted()))
                    .reduce(0, Math::addExact);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("hand must conserve its initial chips", exception);
        }
        if (actualTotal != initialTotalChips) {
            throw new IllegalArgumentException("hand must conserve its initial chips");
        }
    }

    private static int firstPreflopActorSeat(
            List<SeatState> seats, int playerCount, int buttonSeat, int bigBlindSeat) {
        int startingSeat = playerCount == 2 ? Math.floorMod(buttonSeat - 1, TABLE_SIZE) : bigBlindSeat;
        return nextActiveSeat(seats, startingSeat);
    }

    private static boolean preflopActionRequired(List<SeatState> seats) {
        List<SeatState> activeSeats = seats.stream()
                .filter(seat -> seat.status() == ACTIVE)
                .toList();
        if (activeSeats.isEmpty()) {
            return false;
        }
        if (activeSeats.size() > 1) {
            return true;
        }
        long currentBet = seats.stream()
                .mapToLong(SeatState::streetCommitted)
                .max()
                .orElse(0);
        return activeSeats.getFirst().streetCommitted() < currentBet;
    }

    private static HandTransition advanceStreet(
            Hand completedRoundHand, List<HandEvent> precedingEvents) {
        List<SeatState> resetSeats = completedRoundHand.seats.stream()
                .map(SeatState::resetStreet)
                .toList();
        Hand resetHand = new Hand(
                completedRoundHand.id,
                completedRoundHand.buttonSeat,
                completedRoundHand.blinds,
                completedRoundHand.deck,
                resetSeats,
                completedRoundHand.holeCards,
                completedRoundHand.board,
                completedRoundHand.burnedCards,
                null,
                completedRoundHand.street,
                false,
                completedRoundHand.initialTotalChips);
        CommunityDeal deal = resetHand.dealNextStreet();
        Hand dealtHand = deal.hand();
        int firstToActSeat = nextActiveSeat(dealtHand.seats, dealtHand.buttonSeat);
        BettingRound nextRound = BettingRound.start(
                dealtHand.seats,
                dealtHand.street,
                firstToActSeat,
                dealtHand.blinds.bigBlind());
        Hand nextHand = new Hand(
                dealtHand.id,
                dealtHand.buttonSeat,
                dealtHand.blinds,
                dealtHand.deck,
                nextRound.seats(),
                dealtHand.holeCards,
                dealtHand.board,
                dealtHand.burnedCards,
                nextRound,
                dealtHand.street,
                false,
                dealtHand.initialTotalChips);
        List<HandEvent> events = new ArrayList<>(precedingEvents);
        events.add(deal.event());
        return new HandTransition(nextHand, events);
    }

    private static boolean noFurtherBettingPossible(List<SeatState> seats) {
        return seats.stream().filter(seat -> seat.status() == ACTIVE).limit(2).count() <= 1;
    }

    private static long nonFoldedCount(List<SeatState> seats) {
        return seats.stream()
                .filter(seat -> seat.status() != com.agenttavern.game.betting.PlayerStatus.FOLDED)
                .filter(seat -> seat.status() != com.agenttavern.game.betting.PlayerStatus.OUT)
                .count();
    }

    private static HandTransition runoutAndSettle(
            Hand startingHand, List<HandEvent> precedingEvents) {
        Hand hand = startingHand;
        List<HandEvent> events = new ArrayList<>(precedingEvents);
        while (hand.street != Street.RIVER) {
            CommunityDeal deal = hand.dealNextStreet();
            hand = deal.hand();
            events.add(deal.event());
        }
        return settleShowdown(hand, events);
    }

    private CommunityDeal dealNextStreet() {
        Street nextStreet;
        int communityCardCount;
        if (street == PREFLOP) {
            nextStreet = Street.FLOP;
            communityCardCount = 3;
        } else if (street == Street.FLOP) {
            nextStreet = Street.TURN;
            communityCardCount = 1;
        } else if (street == Street.TURN) {
            nextStreet = Street.RIVER;
            communityCardCount = 1;
        } else {
            throw new IllegalStateException("river has no next community street");
        }

        Deck.Draw burn = deck.draw();
        Deck remainingDeck = burn.deck();
        List<Card> dealtCards = new ArrayList<>(communityCardCount);
        for (int index = 0; index < communityCardCount; index++) {
            Deck.Draw draw = remainingDeck.draw();
            dealtCards.add(draw.card());
            remainingDeck = draw.deck();
        }
        List<Card> nextBoard = new ArrayList<>(board);
        nextBoard.addAll(dealtCards);
        List<Card> nextBurnedCards = new ArrayList<>(burnedCards);
        nextBurnedCards.add(burn.card());
        Hand nextHand = new Hand(
                id,
                buttonSeat,
                blinds,
                remainingDeck,
                seats,
                holeCards,
                nextBoard,
                nextBurnedCards,
                null,
                nextStreet,
                false,
                initialTotalChips);
        return new CommunityDeal(
                nextHand, new HandEvent.CommunityCardsDealt(id, nextStreet, dealtCards));
    }

    private static HandTransition settleShowdown(Hand hand, List<HandEvent> precedingEvents) {
        List<Pot> pots = SidePotCalculator.calculate(hand.seats);
        Map<PlayerId, Integer> playerSeats = new LinkedHashMap<>();
        hand.seats.forEach(seat -> playerSeats.put(seat.playerId(), seat.seatIndex()));
        Map<PlayerId, Long> payouts = ShowdownResolver.resolve(
                pots, hand.holeCards, hand.board, playerSeats, hand.buttonSeat);
        return settle(hand, pots, payouts, Street.SHOWDOWN, precedingEvents);
    }

    private static HandTransition settleUncontested(
            Hand hand, List<HandEvent> precedingEvents) {
        List<Pot> pots = SidePotCalculator.calculate(hand.seats);
        PlayerId winner = hand.seats.stream()
                .filter(seat -> seat.status() != com.agenttavern.game.betting.PlayerStatus.FOLDED)
                .filter(seat -> seat.status() != com.agenttavern.game.betting.PlayerStatus.OUT)
                .findFirst()
                .orElseThrow()
                .playerId();
        long totalPot = pots.stream().mapToLong(Pot::amount).reduce(0, Math::addExact);
        return settle(hand, pots, Map.of(winner, totalPot), hand.street, precedingEvents);
    }

    private static HandTransition settle(
            Hand hand,
            List<Pot> pots,
            Map<PlayerId, Long> payouts,
            Street terminalStreet,
            List<HandEvent> precedingEvents) {
        Map<PlayerId, Long> orderedPayouts = new LinkedHashMap<>();
        hand.seats.stream()
                .sorted(Comparator.comparingInt(SeatState::seatIndex))
                .filter(seat -> payouts.containsKey(seat.playerId()))
                .forEach(seat -> orderedPayouts.put(
                        seat.playerId(), payouts.get(seat.playerId())));
        List<SeatState> settledSeats = hand.seats.stream()
                .map(seat -> seat.settled(orderedPayouts.getOrDefault(seat.playerId(), 0L)))
                .toList();
        Hand completedHand = new Hand(
                hand.id,
                hand.buttonSeat,
                hand.blinds,
                hand.deck,
                settledSeats,
                hand.holeCards,
                hand.board,
                hand.burnedCards,
                null,
                terminalStreet,
                true,
                hand.initialTotalChips);
        completedHand.totalChipsInSystem();
        List<HandEvent> events = new ArrayList<>(precedingEvents);
        events.add(new HandEvent.PotsAwarded(hand.id, pots, orderedPayouts));
        events.add(new HandEvent.HandCompleted(hand.id, settledSeats));
        return new HandTransition(completedHand, events);
    }

    private static int nextActiveSeat(List<SeatState> seats, int startingSeat) {
        return seats.stream()
                .filter(seat -> seat.status() == ACTIVE)
                .min(Comparator.comparingInt(seat -> clockwiseDistance(seat.seatIndex(), startingSeat)))
                .orElseThrow(() -> new IllegalStateException("hand has no active player"))
                .seatIndex();
    }

    private static List<PlayerStack> clockwisePlayers(List<PlayerStack> players, int startingSeat) {
        return players.stream()
                .sorted(Comparator.comparingInt(
                        player -> clockwiseDistance(player.seatIndex(), startingSeat)))
                .toList();
    }

    private static int clockwiseDistance(int seat, int startingSeat) {
        int distance = Math.floorMod(seat - startingSeat, TABLE_SIZE);
        return distance == 0 ? TABLE_SIZE : distance;
    }

    private static PlayerStack nextPlayer(List<PlayerStack> players, int startingSeat) {
        return clockwisePlayers(players, startingSeat).getFirst();
    }

    private static PlayerStack playerAtSeat(List<PlayerStack> players, int seatIndex) {
        return players.stream()
                .filter(player -> player.seatIndex() == seatIndex)
                .findFirst()
                .orElseThrow();
    }

    private static SeatState seat(List<SeatState> seats, PlayerId playerId) {
        return seats.stream()
                .filter(seat -> seat.playerId().equals(playerId))
                .findFirst()
                .orElseThrow();
    }

    private static List<SeatState> replace(List<SeatState> seats, SeatState replacement) {
        return seats.stream()
                .map(seat -> seat.playerId().equals(replacement.playerId()) ? replacement : seat)
                .toList();
    }

    private static Map<PlayerId, List<Card>> immutableHoleCards(
            Map<PlayerId, List<Card>> source) {
        Map<PlayerId, List<Card>> copy = new LinkedHashMap<>();
        source.forEach((playerId, cards) -> copy.put(playerId, List.copyOf(cards)));
        return Collections.unmodifiableMap(copy);
    }

    private record CommunityDeal(Hand hand, HandEvent.CommunityCardsDealt event) {}
}
