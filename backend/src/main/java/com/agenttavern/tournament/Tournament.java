package com.agenttavern.tournament;

import static java.util.Objects.requireNonNull;

import com.agenttavern.game.betting.PlayerAction;
import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.betting.SeatState;
import com.agenttavern.game.card.Deck;
import com.agenttavern.game.hand.BlindLevel;
import com.agenttavern.game.hand.Hand;
import com.agenttavern.game.hand.HandCheckpoint;
import com.agenttavern.game.hand.HandEvent;
import com.agenttavern.game.hand.HandId;
import com.agenttavern.game.hand.HandTransition;
import com.agenttavern.game.hand.PlayerStack;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class Tournament {

    private static final int TABLE_SIZE = 6;
    private static final long INITIAL_STACK = 10_000L;
    private static final long TOTAL_CHIPS = INITIAL_STACK * TABLE_SIZE;
    private static final BlindSchedule BLIND_SCHEDULE = BlindSchedule.standard();

    private final TournamentId id;
    private final TournamentMode mode;
    private final TournamentStatus status;
    private final List<TournamentSeat> seats;
    private final int buttonSeat;
    private final int completedHands;
    private final int blindLevelIndex;
    private final Hand currentHand;
    private final Map<PlayerId, Long> currentHandStartingStacks;

    private Tournament(
            TournamentId id,
            TournamentMode mode,
            TournamentStatus status,
            List<TournamentSeat> seats,
            int buttonSeat,
            int completedHands,
            int blindLevelIndex,
            Hand currentHand,
            Map<PlayerId, Long> currentHandStartingStacks) {
        this.id = requireNonNull(id, "id");
        this.mode = requireNonNull(mode, "mode");
        this.status = requireNonNull(status, "status");
        this.seats = List.copyOf(seats);
        this.buttonSeat = buttonSeat;
        this.completedHands = completedHands;
        this.blindLevelIndex = blindLevelIndex;
        this.currentHand = currentHand;
        this.currentHandStartingStacks = immutableStartingStacks(currentHandStartingStacks);
    }

    public static TournamentTransition start(
            TournamentId id,
            TournamentMode mode,
            List<TournamentEntrant> entrants,
            int firstButtonSeat,
            HandId firstHandId,
            Deck firstDeck) {
        requireNonNull(id, "id");
        requireNonNull(mode, "mode");
        List<TournamentEntrant> validatedEntrants = validateEntrants(entrants);
        validateSeatIndex(firstButtonSeat, "first button seat");
        requireNonNull(firstHandId, "firstHandId");
        requireNonNull(firstDeck, "firstDeck");

        List<TournamentSeat> initialSeats = validatedEntrants.stream()
                .map(entrant -> new TournamentSeat(
                        entrant.playerId(),
                        entrant.seatIndex(),
                        INITIAL_STACK,
                        TournamentSeatStatus.FUNDED,
                        null))
                .toList();
        Map<PlayerId, Long> startingStacks = startingStacks(initialSeats);
        HandTransition handTransition = Hand.start(
                firstHandId,
                playerStacks(initialSeats),
                firstButtonSeat,
                BLIND_SCHEDULE.forCompletedHands(0),
                firstDeck);
        Tournament tournament = new Tournament(
                id,
                mode,
                TournamentStatus.IN_HAND,
                mirrorHandStacks(initialSeats, handTransition.hand()),
                firstButtonSeat,
                0,
                0,
                handTransition.hand(),
                startingStacks);

        List<TournamentEvent> events = new ArrayList<>();
        events.add(new TournamentEvent.TournamentStarted(id, mode, initialSeats, firstButtonSeat));
        appendRecordedHandEvents(events, id, handTransition.events());
        return tournament.afterHandTransition(handTransition.hand(), events);
    }

    public TournamentTransition act(PlayerId actorId, PlayerAction action) {
        if (status != TournamentStatus.IN_HAND) {
            throw new IllegalStateException("only an in-progress hand accepts actions");
        }
        HandTransition handTransition = currentHand.act(actorId, action);
        List<TournamentEvent> events = new ArrayList<>();
        appendRecordedHandEvents(events, id, handTransition.events());
        Tournament tournament = new Tournament(
                id,
                mode,
                TournamentStatus.IN_HAND,
                mirrorHandStacks(seats, handTransition.hand()),
                buttonSeat,
                completedHands,
                blindLevelIndex,
                handTransition.hand(),
                currentHandStartingStacks);
        return tournament.afterHandTransition(handTransition.hand(), events);
    }

    public TournamentTransition startNextHand(HandId handId, Deck deck) {
        if (status == TournamentStatus.COMPLETE) {
            throw new IllegalStateException("completed tournament has no next hand");
        }
        if (status != TournamentStatus.BETWEEN_HANDS) {
            throw new IllegalStateException("next hand can start only between hands");
        }
        requireNonNull(handId, "handId");
        requireNonNull(deck, "deck");

        List<TournamentSeat> fundedSeats = fundedSeats(seats);
        int nextButtonSeat = nextFundedSeatClockwise(buttonSeat, fundedSeats);
        Map<PlayerId, Long> startingStacks = startingStacks(fundedSeats);
        HandTransition handTransition = Hand.start(
                handId,
                playerStacks(fundedSeats),
                nextButtonSeat,
                currentBlinds(),
                deck);
        Tournament tournament = new Tournament(
                id,
                mode,
                TournamentStatus.IN_HAND,
                mirrorHandStacks(seats, handTransition.hand()),
                nextButtonSeat,
                completedHands,
                blindLevelIndex,
                handTransition.hand(),
                startingStacks);
        List<TournamentEvent> events = new ArrayList<>();
        appendRecordedHandEvents(events, id, handTransition.events());
        return tournament.afterHandTransition(handTransition.hand(), events);
    }

    public TournamentId id() {
        return id;
    }

    public TournamentMode mode() {
        return mode;
    }

    public TournamentStatus status() {
        return status;
    }

    public List<TournamentSeat> seats() {
        return seats;
    }

    public int buttonSeat() {
        return buttonSeat;
    }

    public int completedHands() {
        return completedHands;
    }

    public int blindLevelIndex() {
        return blindLevelIndex;
    }

    public BlindLevel currentBlinds() {
        return BLIND_SCHEDULE.forLevelIndex(blindLevelIndex);
    }

    public TournamentCheckpoint checkpoint() {
        return new TournamentCheckpoint(
                id,
                mode,
                status,
                seats,
                buttonSeat,
                completedHands,
                blindLevelIndex,
                Optional.ofNullable(currentHand).map(Hand::checkpoint),
                currentHandStartingStacks);
    }

    public static Tournament restore(TournamentCheckpoint checkpoint) {
        requireNonNull(checkpoint, "checkpoint");
        Hand restoredHand = checkpoint.currentHand().map(Hand::restore).orElse(null);
        validateCheckpoint(checkpoint, restoredHand);
        return new Tournament(
                checkpoint.id(),
                checkpoint.mode(),
                checkpoint.status(),
                checkpoint.seats(),
                checkpoint.buttonSeat(),
                checkpoint.completedHands(),
                checkpoint.blindLevelIndex(),
                restoredHand,
                checkpoint.currentHandStartingStacks());
    }

    public Hand currentHand() {
        if (status != TournamentStatus.IN_HAND || currentHand == null) {
            throw new IllegalStateException("current hand is available only while a hand is in progress");
        }
        return currentHand;
    }

    private TournamentTransition afterHandTransition(Hand updatedHand, List<TournamentEvent> events) {
        if (!updatedHand.isComplete()) {
            return new TournamentTransition(this, events);
        }

        int nextCompletedHands = Math.incrementExact(completedHands);
        List<TournamentSeat> resolvedSeats = resolveEliminations(events);
        int nextBlindLevelIndex = BLIND_SCHEDULE.levelIndexForCompletedHands(nextCompletedHands);
        if (nextBlindLevelIndex > blindLevelIndex) {
            events.add(new TournamentEvent.BlindLevelAdvanced(
                    id, nextBlindLevelIndex, BLIND_SCHEDULE.forLevelIndex(nextBlindLevelIndex)));
        }

        List<TournamentSeat> fundedSeats = fundedSeats(resolvedSeats);
        if (fundedSeats.size() == 1) {
            TournamentSeat winner = fundedSeats.getFirst().winner();
            resolvedSeats = replaceSeat(resolvedSeats, winner);
            events.add(new TournamentEvent.TournamentCompleted(id, winner.playerId(), resolvedSeats));
            return new TournamentTransition(new Tournament(
                    id,
                    mode,
                    TournamentStatus.COMPLETE,
                    resolvedSeats,
                    buttonSeat,
                    nextCompletedHands,
                    nextBlindLevelIndex,
                    null,
                    Map.of()), events);
        }

        return new TournamentTransition(new Tournament(
                id,
                mode,
                TournamentStatus.BETWEEN_HANDS,
                resolvedSeats,
                buttonSeat,
                nextCompletedHands,
                nextBlindLevelIndex,
                null,
                Map.of()), events);
    }

    private List<TournamentSeat> resolveEliminations(List<TournamentEvent> events) {
        int fundedSeatCount = fundedSeats(seats).size();
        List<TournamentSeat> eliminated = seats.stream()
                .filter(seat -> seat.status() == TournamentSeatStatus.FUNDED && seat.stack() == 0)
                .sorted(Comparator
                        .comparingLong((TournamentSeat seat) -> handStartingStack(seat.playerId()))
                        .thenComparingInt(seat -> clockwiseDistanceFromButtonLeft(seat.seatIndex())))
                .toList();
        List<TournamentSeat> resolved = seats;
        for (int index = 0; index < eliminated.size(); index++) {
            TournamentSeat eliminatedSeat = eliminated.get(index);
            int finishPosition = Math.subtractExact(fundedSeatCount, index);
            TournamentSeat updatedSeat = eliminatedSeat.eliminated(finishPosition);
            resolved = replaceSeat(resolved, updatedSeat);
            events.add(new TournamentEvent.PlayerEliminated(id, updatedSeat.playerId(), finishPosition));
        }
        return resolved;
    }

    private long handStartingStack(PlayerId playerId) {
        Long startingStack = currentHandStartingStacks.get(playerId);
        if (startingStack == null) {
            throw new IllegalStateException("funded hand player has no recorded starting stack");
        }
        return startingStack;
    }

    private int clockwiseDistanceFromButtonLeft(int seatIndex) {
        return Math.floorMod(seatIndex - buttonSeat - 1, TABLE_SIZE);
    }

    private static List<TournamentEntrant> validateEntrants(List<TournamentEntrant> entrants) {
        requireNonNull(entrants, "entrants");
        if (entrants.size() != TABLE_SIZE) {
            throw new IllegalArgumentException("a tournament requires exactly six entrants");
        }
        List<TournamentEntrant> validated = entrants.stream()
                .map(entrant -> requireNonNull(entrant, "entrant"))
                .sorted(Comparator.comparingInt(TournamentEntrant::seatIndex))
                .toList();
        Set<PlayerId> playerIds = new HashSet<>();
        Set<Integer> seatIndexes = new HashSet<>();
        for (TournamentEntrant entrant : validated) {
            if (!playerIds.add(entrant.playerId())) {
                throw new IllegalArgumentException("tournament player IDs must be unique");
            }
            if (!seatIndexes.add(entrant.seatIndex())) {
                throw new IllegalArgumentException("tournament seat indexes must be unique");
            }
        }
        for (int seatIndex = 0; seatIndex < TABLE_SIZE; seatIndex++) {
            if (!seatIndexes.contains(seatIndex)) {
                throw new IllegalArgumentException("tournament entrants must occupy seats zero through five");
            }
        }
        return validated;
    }

    private static List<PlayerStack> playerStacks(List<TournamentSeat> seats) {
        return seats.stream()
                .map(seat -> new PlayerStack(seat.playerId(), seat.seatIndex(), seat.stack()))
                .toList();
    }

    private static Map<PlayerId, Long> startingStacks(List<TournamentSeat> seats) {
        Map<PlayerId, Long> startingStacks = new LinkedHashMap<>();
        for (TournamentSeat seat : seats) {
            startingStacks.put(seat.playerId(), seat.stack());
        }
        return startingStacks;
    }

    private static List<TournamentSeat> mirrorHandStacks(
            List<TournamentSeat> tournamentSeats, Hand hand) {
        Map<PlayerId, Long> handStacks = new LinkedHashMap<>();
        for (SeatState handSeat : hand.seats()) {
            handStacks.put(handSeat.playerId(), handSeat.stack());
        }
        return tournamentSeats.stream()
                .map(seat -> handStacks.containsKey(seat.playerId())
                        ? seat.withStack(handStacks.get(seat.playerId()))
                        : seat)
                .toList();
    }

    private static List<TournamentSeat> fundedSeats(List<TournamentSeat> seats) {
        return seats.stream()
                .filter(seat -> seat.status() == TournamentSeatStatus.FUNDED)
                .toList();
    }

    private static int nextFundedSeatClockwise(int fromSeat, List<TournamentSeat> fundedSeats) {
        for (int offset = 1; offset <= TABLE_SIZE; offset++) {
            int candidate = Math.floorMod(fromSeat + offset, TABLE_SIZE);
            boolean isFunded = fundedSeats.stream().anyMatch(seat -> seat.seatIndex() == candidate);
            if (isFunded) {
                return candidate;
            }
        }
        throw new IllegalStateException("cannot find a funded seat for the next button");
    }

    private static List<TournamentSeat> replaceSeat(
            List<TournamentSeat> seats, TournamentSeat replacement) {
        return seats.stream()
                .map(seat -> seat.playerId().equals(replacement.playerId()) ? replacement : seat)
                .toList();
    }

    private static void appendRecordedHandEvents(
            List<TournamentEvent> tournamentEvents,
            TournamentId tournamentId,
            List<HandEvent> handEvents) {
        handEvents.forEach(handEvent ->
                tournamentEvents.add(new TournamentEvent.HandEventRecorded(tournamentId, handEvent)));
    }

    private static void validateSeatIndex(int seatIndex, String label) {
        if (seatIndex < 0 || seatIndex >= TABLE_SIZE) {
            throw new IllegalArgumentException(label + " must be between 0 and 5");
        }
    }

    private static void validateCheckpoint(TournamentCheckpoint checkpoint, Hand restoredHand) {
        validateSeatLayout(checkpoint.seats());
        validateSeatIndex(checkpoint.buttonSeat(), "button seat");
        if (checkpoint.completedHands() < 0) {
            throw new IllegalArgumentException("completed hands must be non-negative");
        }
        int expectedBlindLevel = BLIND_SCHEDULE.levelIndexForCompletedHands(checkpoint.completedHands());
        if (checkpoint.blindLevelIndex() != expectedBlindLevel) {
            throw new IllegalArgumentException("blind-level index must match completed hands");
        }

        validateRanks(checkpoint.status(), checkpoint.seats());
        switch (checkpoint.status()) {
            case IN_HAND -> validateInProgressHand(checkpoint, restoredHand);
            case BETWEEN_HANDS -> validateNoCurrentHand(checkpoint, "between hands");
            case COMPLETE -> validateNoCurrentHand(checkpoint, "completed tournament");
        }
        validateChipConservation(checkpoint.seats(), restoredHand);
    }

    private static void validateSeatLayout(List<TournamentSeat> checkpointSeats) {
        if (checkpointSeats.size() != TABLE_SIZE) {
            throw new IllegalArgumentException("a tournament checkpoint must contain six seats");
        }
        Set<PlayerId> playerIds = new HashSet<>();
        Set<Integer> seatIndexes = new HashSet<>();
        for (TournamentSeat seat : checkpointSeats) {
            if (!playerIds.add(seat.playerId())) {
                throw new IllegalArgumentException("tournament checkpoint player IDs must be unique");
            }
            if (!seatIndexes.add(seat.seatIndex())) {
                throw new IllegalArgumentException("tournament checkpoint seat indexes must be unique");
            }
        }
        for (int seatIndex = 0; seatIndex < TABLE_SIZE; seatIndex++) {
            if (!seatIndexes.contains(seatIndex)) {
                throw new IllegalArgumentException("tournament checkpoint seats must occupy zero through five");
            }
        }
    }

    private static void validateRanks(TournamentStatus status, List<TournamentSeat> checkpointSeats) {
        List<TournamentSeat> funded = fundedSeats(checkpointSeats);
        List<TournamentSeat> eliminated = checkpointSeats.stream()
                .filter(seat -> seat.status() == TournamentSeatStatus.ELIMINATED)
                .toList();
        List<TournamentSeat> winners = checkpointSeats.stream()
                .filter(seat -> seat.status() == TournamentSeatStatus.WINNER)
                .toList();

        Set<Integer> assignedPositions = new HashSet<>();
        for (TournamentSeat seat : checkpointSeats) {
            if (seat.finishPosition() != null && !assignedPositions.add(seat.finishPosition())) {
                throw new IllegalArgumentException("tournament finish positions must be unique");
            }
        }

        if (status == TournamentStatus.COMPLETE) {
            if (!funded.isEmpty() || winners.size() != 1 || eliminated.size() != TABLE_SIZE - 1) {
                throw new IllegalArgumentException(
                        "completed tournament must have one winner and five eliminated seats");
            }
            if (!assignedPositions.equals(Set.of(1, 2, 3, 4, 5, 6))) {
                throw new IllegalArgumentException(
                        "completed tournament finish positions must be one through six");
            }
            return;
        }

        if (!winners.isEmpty()) {
            throw new IllegalArgumentException("only a completed tournament may have a winner");
        }
        if (funded.size() < 2) {
            throw new IllegalArgumentException("an incomplete tournament must have at least two funded seats");
        }
        Set<Integer> expectedPositions = new HashSet<>();
        for (int position = TABLE_SIZE - eliminated.size() + 1; position <= TABLE_SIZE; position++) {
            expectedPositions.add(position);
        }
        if (!assignedPositions.equals(expectedPositions)) {
            throw new IllegalArgumentException(
                    "eliminated tournament positions must be contiguous from the worst position");
        }
        if (status == TournamentStatus.BETWEEN_HANDS
                && funded.stream().anyMatch(seat -> seat.stack() == 0)) {
            throw new IllegalArgumentException("funded seats between hands must have chips");
        }
    }

    private static void validateInProgressHand(TournamentCheckpoint checkpoint, Hand restoredHand) {
        if (restoredHand == null) {
            throw new IllegalArgumentException("in-progress tournament must include a current hand");
        }
        HandCheckpoint handCheckpoint = checkpoint.currentHand().orElseThrow();
        if (restoredHand.isComplete()) {
            throw new IllegalArgumentException("in-progress tournament cannot retain a completed hand");
        }
        if (handCheckpoint.buttonSeat() != checkpoint.buttonSeat()) {
            throw new IllegalArgumentException("tournament and hand buttons must match");
        }
        if (!handCheckpoint.blinds().equals(BLIND_SCHEDULE.forLevelIndex(checkpoint.blindLevelIndex()))) {
            throw new IllegalArgumentException("current hand blinds must match the blind-level index");
        }

        List<TournamentSeat> funded = fundedSeats(checkpoint.seats());
        Map<PlayerId, TournamentSeat> fundedByPlayer = new LinkedHashMap<>();
        for (TournamentSeat seat : funded) {
            fundedByPlayer.put(seat.playerId(), seat);
        }
        Set<PlayerId> handPlayers = new HashSet<>();
        for (SeatState handSeat : restoredHand.seats()) {
            handPlayers.add(handSeat.playerId());
        }
        if (!handPlayers.equals(fundedByPlayer.keySet())) {
            throw new IllegalArgumentException(
                    "current hand seats must match exactly the tournament funded seats");
        }
        if (!checkpoint.currentHandStartingStacks().keySet().equals(handPlayers)) {
            throw new IllegalArgumentException(
                    "current hand starting stacks must match exactly the current hand players");
        }
        boolean buttonIsFundedHandSeat = restoredHand.seats().stream()
                .anyMatch(seat -> seat.seatIndex() == checkpoint.buttonSeat()
                        && fundedByPlayer.containsKey(seat.playerId()));
        if (!buttonIsFundedHandSeat) {
            throw new IllegalArgumentException("in-progress tournament button must be a funded hand seat");
        }
        for (SeatState handSeat : restoredHand.seats()) {
            TournamentSeat tournamentSeat = fundedByPlayer.get(handSeat.playerId());
            if (tournamentSeat.seatIndex() != handSeat.seatIndex()) {
                throw new IllegalArgumentException("tournament and hand seat indexes must match");
            }
            if (tournamentSeat.stack() != handSeat.stack()) {
                throw new IllegalArgumentException("tournament and hand stacks must match");
            }
            long startingStack = checkpoint.currentHandStartingStacks().get(handSeat.playerId());
            if (startingStack < 0) {
                throw new IllegalArgumentException("current hand starting stacks must be non-negative");
            }
            long expectedStartingStack;
            try {
                expectedStartingStack = Math.addExact(handSeat.stack(), handSeat.handCommitted());
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("current hand stack total overflowed", exception);
            }
            if (startingStack != expectedStartingStack) {
                throw new IllegalArgumentException(
                        "current hand starting stacks must reflect stacks before blinds and actions");
            }
        }
    }

    private static void validateNoCurrentHand(TournamentCheckpoint checkpoint, String state) {
        if (checkpoint.currentHand().isPresent() || !checkpoint.currentHandStartingStacks().isEmpty()) {
            throw new IllegalArgumentException(state + " tournament cannot retain a current hand");
        }
    }

    private static void validateChipConservation(List<TournamentSeat> checkpointSeats, Hand restoredHand) {
        try {
            long tournamentStacks = checkpointSeats.stream()
                    .mapToLong(TournamentSeat::stack)
                    .reduce(0, Math::addExact);
            long committedChips = restoredHand == null ? 0 : restoredHand.seats().stream()
                    .mapToLong(SeatState::handCommitted)
                    .reduce(0, Math::addExact);
            if (Math.addExact(tournamentStacks, committedChips) != TOTAL_CHIPS) {
                throw new IllegalArgumentException("tournament checkpoint must conserve 60000 chips");
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("tournament checkpoint chip total overflowed", exception);
        }
    }

    private static Map<PlayerId, Long> immutableStartingStacks(Map<PlayerId, Long> source) {
        Map<PlayerId, Long> copy = new LinkedHashMap<>();
        source.forEach((playerId, stack) -> copy.put(
                requireNonNull(playerId, "current hand player"),
                requireNonNull(stack, "current hand starting stack")));
        return Collections.unmodifiableMap(copy);
    }
}
