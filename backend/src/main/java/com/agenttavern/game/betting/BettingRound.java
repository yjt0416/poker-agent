package com.agenttavern.game.betting;

import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class BettingRound {

    private static final int TABLE_SIZE = 6;

    private final List<SeatState> seats;
    private final Street street;
    private final long currentBet;
    private final long lastFullRaiseSize;
    private final Set<PlayerId> pendingAction;
    private final Set<PlayerId> raiseRights;
    private final PlayerId actorId;

    private BettingRound(
            List<SeatState> seats,
            Street street,
            long currentBet,
            long lastFullRaiseSize,
            Set<PlayerId> pendingAction,
            Set<PlayerId> raiseRights,
            PlayerId actorId) {
        this.seats = List.copyOf(seats);
        this.street = street;
        this.currentBet = currentBet;
        this.lastFullRaiseSize = lastFullRaiseSize;
        this.pendingAction = Set.copyOf(pendingAction);
        this.raiseRights = Set.copyOf(raiseRights);
        this.actorId = actorId;
    }

    public static BettingRound start(
            List<SeatState> seats,
            Street street,
            int firstToActSeat,
            long lastFullRaiseSize) {
        requireNonNull(seats, "seats");
        requireNonNull(street, "street");
        List<SeatState> copiedSeats = List.copyOf(seats);
        if (copiedSeats.size() < 2 || copiedSeats.size() > TABLE_SIZE) {
            throw new IllegalArgumentException("a betting round requires two to six seats");
        }
        if (lastFullRaiseSize <= 0) {
            throw new IllegalArgumentException("lastFullRaiseSize must be positive");
        }

        Set<Integer> seatIndexes = new HashSet<>();
        Set<PlayerId> playerIds = new HashSet<>();
        for (SeatState seat : copiedSeats) {
            if (!seatIndexes.add(seat.seatIndex())) {
                throw new IllegalArgumentException("seat indexes must be unique");
            }
            if (!playerIds.add(seat.playerId())) {
                throw new IllegalArgumentException("player IDs must be unique");
            }
        }
        SeatState firstActor = copiedSeats.stream()
                .filter(seat -> seat.seatIndex() == firstToActSeat)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("first actor seat must be occupied"));
        if (firstActor.status() != PlayerStatus.ACTIVE) {
            throw new IllegalArgumentException("first actor must be active");
        }

        long currentBet = copiedSeats.stream()
                .mapToLong(SeatState::streetCommitted)
                .max()
                .orElse(0);
        Set<PlayerId> activePlayers = activePlayers(copiedSeats);
        return create(
                copiedSeats,
                street,
                currentBet,
                lastFullRaiseSize,
                activePlayers,
                activePlayers,
                firstActor.playerId());
    }

    public SeatState actor() {
        if (actorId == null) {
            return null;
        }
        return seat(actorId);
    }

    public LegalActions legalActions() {
        SeatState actor = actor();
        if (actor == null) {
            throw new IllegalStateException("completed betting round has no legal actions");
        }
        return LegalActions.calculate(
                actor, currentBet, lastFullRaiseSize, raiseRights.contains(actor.playerId()));
    }

    public BettingRound apply(PlayerAction action) {
        requireNonNull(action, "action");
        SeatState actor = actor();
        if (actor == null) {
            throw new IllegalActionException("betting round is complete");
        }
        LegalActions legal = legalActions();
        if (!legal.types().contains(action.type())) {
            throw new IllegalActionException("action is not currently legal: " + action.type());
        }

        return switch (action.type()) {
            case CHECK -> completePassiveAction(actor, actor);
            case CALL -> completePassiveAction(actor, actor.withContribution(legal.callAmount()));
            case RAISE -> applyRaise(actor, action.amount(), legal);
            case ALL_IN -> applyAllIn(actor, action.amount(), legal);
            case FOLD -> completePassiveAction(actor, actor.fold());
        };
    }

    public boolean isComplete() {
        return actorId == null;
    }

    public List<SeatState> seats() {
        return seats;
    }

    public long currentBet() {
        return currentBet;
    }

    public long lastFullRaiseSize() {
        return lastFullRaiseSize;
    }

    public Street street() {
        return street;
    }

    private BettingRound completePassiveAction(SeatState actor, SeatState updatedActor) {
        List<SeatState> updatedSeats = replaceSeat(updatedActor);
        Set<PlayerId> updatedPending = without(pendingAction, actor.playerId());
        Set<PlayerId> updatedRaiseRights = without(raiseRights, actor.playerId());
        return nextState(
                updatedSeats,
                currentBet,
                lastFullRaiseSize,
                updatedPending,
                updatedRaiseRights,
                actor.seatIndex());
    }

    private BettingRound applyRaise(SeatState actor, long target, LegalActions legal) {
        if (legal.minRaiseTo().isEmpty()
                || target < legal.minRaiseTo().getAsLong()
                || target > legal.maxRaiseTo()) {
            throw new IllegalActionException("raise target is outside the legal range");
        }
        SeatState updatedActor = actor.withContribution(
                Math.subtractExact(target, actor.streetCommitted()));
        List<SeatState> updatedSeats = replaceSeat(updatedActor);
        return applyFullRaise(actor, updatedSeats, target);
    }

    private BettingRound applyAllIn(SeatState actor, long target, LegalActions legal) {
        if (target != legal.maxRaiseTo()) {
            throw new IllegalActionException("all-in target must equal the actor's full stack target");
        }
        SeatState updatedActor = actor.withContribution(actor.stack());
        List<SeatState> updatedSeats = replaceSeat(updatedActor);
        if (target <= currentBet) {
            return completePassiveAction(actor, updatedActor);
        }

        long raiseSize = Math.subtractExact(target, currentBet);
        if (raiseSize >= lastFullRaiseSize) {
            return applyFullRaise(actor, updatedSeats, target);
        }

        Set<PlayerId> updatedPending = without(pendingAction, actor.playerId());
        for (SeatState seat : updatedSeats) {
            if (seat.status() == PlayerStatus.ACTIVE && seat.streetCommitted() < target) {
                updatedPending.add(seat.playerId());
            }
        }
        Set<PlayerId> updatedRaiseRights = without(raiseRights, actor.playerId());
        return nextState(
                updatedSeats,
                target,
                lastFullRaiseSize,
                updatedPending,
                updatedRaiseRights,
                actor.seatIndex());
    }

    private BettingRound applyFullRaise(
            SeatState actor, List<SeatState> updatedSeats, long target) {
        Set<PlayerId> otherActivePlayers = activePlayers(updatedSeats);
        otherActivePlayers.remove(actor.playerId());
        long updatedRaiseSize = Math.subtractExact(target, currentBet);
        return nextState(
                updatedSeats,
                target,
                updatedRaiseSize,
                otherActivePlayers,
                otherActivePlayers,
                actor.seatIndex());
    }

    private BettingRound nextState(
            List<SeatState> updatedSeats,
            long updatedCurrentBet,
            long updatedLastFullRaiseSize,
            Set<PlayerId> updatedPending,
            Set<PlayerId> updatedRaiseRights,
            int previousActorSeat) {
        PlayerId nextActor = nextActor(updatedSeats, updatedPending, previousActorSeat);
        return create(
                updatedSeats,
                street,
                updatedCurrentBet,
                updatedLastFullRaiseSize,
                updatedPending,
                updatedRaiseRights,
                nextActor);
    }

    private static BettingRound create(
            List<SeatState> seats,
            Street street,
            long currentBet,
            long lastFullRaiseSize,
            Set<PlayerId> pendingAction,
            Set<PlayerId> raiseRights,
            PlayerId proposedActor) {
        boolean onePlayerRemains = seats.stream()
                .filter(seat -> seat.status() != PlayerStatus.FOLDED)
                .filter(seat -> seat.status() != PlayerStatus.OUT)
                .limit(2)
                .count() <= 1;
        if (onePlayerRemains || pendingAction.isEmpty()) {
            return new BettingRound(
                    seats, street, currentBet, lastFullRaiseSize, Set.of(), Set.of(), null);
        }
        return new BettingRound(
                seats,
                street,
                currentBet,
                lastFullRaiseSize,
                pendingAction,
                raiseRights,
                proposedActor);
    }

    private static PlayerId nextActor(
            List<SeatState> seats, Set<PlayerId> pendingAction, int previousActorSeat) {
        SeatState next = null;
        int nextDistance = TABLE_SIZE + 1;
        for (SeatState seat : seats) {
            if (seat.status() != PlayerStatus.ACTIVE
                    || !pendingAction.contains(seat.playerId())) {
                continue;
            }
            int distance = Math.floorMod(seat.seatIndex() - previousActorSeat, TABLE_SIZE);
            if (distance == 0) {
                distance = TABLE_SIZE;
            }
            if (distance < nextDistance) {
                next = seat;
                nextDistance = distance;
            }
        }
        return next == null ? null : next.playerId();
    }

    private static Set<PlayerId> activePlayers(List<SeatState> seats) {
        Set<PlayerId> players = new HashSet<>();
        for (SeatState seat : seats) {
            if (seat.status() == PlayerStatus.ACTIVE) {
                players.add(seat.playerId());
            }
        }
        return players;
    }

    private List<SeatState> replaceSeat(SeatState replacement) {
        return seats.stream()
                .map(seat -> seat.playerId().equals(replacement.playerId()) ? replacement : seat)
                .toList();
    }

    private SeatState seat(PlayerId playerId) {
        return seats.stream()
                .filter(seat -> seat.playerId().equals(playerId))
                .findFirst()
                .orElseThrow();
    }

    private static Set<PlayerId> without(Set<PlayerId> players, PlayerId playerId) {
        Set<PlayerId> result = new HashSet<>(players);
        result.remove(playerId);
        return result;
    }
}
