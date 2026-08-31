package com.agenttavern.game.betting;

import static com.agenttavern.game.betting.ActionType.CALL;
import static com.agenttavern.game.betting.ActionType.RAISE;
import static com.agenttavern.game.betting.PlayerStatus.ACTIVE;
import static com.agenttavern.game.betting.PlayerStatus.ALL_IN;
import static com.agenttavern.game.betting.PlayerStatus.FOLDED;
import static com.agenttavern.game.betting.PlayerStatus.OUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BettingRoundTest {

    @Test
    void callCallCheckCompletesThreePlayerRound() {
        BettingRound round = preflopRoundWithBlinds(50, 100);

        round = round.apply(PlayerAction.call());
        round = round.apply(PlayerAction.call());
        round = round.apply(PlayerAction.check());

        assertThat(round.isComplete()).isTrue();
        assertThat(round.actor()).isNull();
        assertThat(round.seats())
                .extracting(SeatState::streetCommitted)
                .containsExactly(100L, 100L, 100L);
    }

    @Test
    void fullRaiseByLastActorReopensActionForEveryOtherActivePlayer() {
        BettingRound round = preflopRoundWithBlinds(50, 100);
        PlayerId firstActor = round.actor().playerId();

        round = round.apply(PlayerAction.call());
        round = round.apply(PlayerAction.call());
        round = round.apply(PlayerAction.raiseTo(300));

        assertThat(round.actor().playerId()).isEqualTo(firstActor);
        assertThat(round.legalActions().types()).contains(CALL, RAISE);
        assertThat(round.currentBet()).isEqualTo(300);
        assertThat(round.lastFullRaiseSize()).isEqualTo(200);

        round = round.apply(PlayerAction.call());
        round = round.apply(PlayerAction.call());

        assertThat(round.isComplete()).isTrue();
        assertThat(round.seats())
                .extracting(SeatState::streetCommitted)
                .containsExactly(300L, 300L, 300L);
    }

    @Test
    void shortAllInRependsUndercalledPlayersWithoutReopeningAPreviousCallersRaiseRight() {
        BettingRound round = round(
                0,
                100,
                seat(0, 900, 100),
                seat(1, 100, 150),
                seat(2, 800, 200));

        round = round.apply(PlayerAction.call());
        round = round.apply(PlayerAction.allIn(250));

        assertThat(round.currentBet()).isEqualTo(250);
        assertThat(round.lastFullRaiseSize()).isEqualTo(100);
        assertThat(round.actor().seatIndex()).isEqualTo(2);
        assertThat(round.legalActions().types()).contains(CALL, RAISE);

        round = round.apply(PlayerAction.call());

        assertThat(round.actor().seatIndex()).isZero();
        assertThat(round.legalActions().types()).contains(CALL).doesNotContain(RAISE);
    }

    @Test
    void fullAllInRaiseReopensRaiseRightsForAPlayerWhoAlreadyCalled() {
        BettingRound round = round(
                0,
                100,
                seat(0, 900, 100),
                seat(1, 150, 150),
                seat(2, 800, 200));

        round = round.apply(PlayerAction.call());
        round = round.apply(PlayerAction.allIn(300));
        round = round.apply(PlayerAction.call());

        assertThat(round.currentBet()).isEqualTo(300);
        assertThat(round.lastFullRaiseSize()).isEqualTo(100);
        assertThat(round.actor().seatIndex()).isZero();
        assertThat(round.legalActions().types()).contains(CALL, RAISE);
    }

    @Test
    void consecutiveShortAllInsKeepTheFullRaiseSizeAndDoNotReopenAPreviousCaller() {
        BettingRound round = round(
                0,
                100,
                seat(0, 900, 100),
                seat(1, 100, 150),
                seat(2, 100, 200),
                seat(3, 800, 200));

        round = round.apply(PlayerAction.call());
        round = round.apply(PlayerAction.allIn(250));
        round = round.apply(PlayerAction.allIn(300));

        assertThat(round.currentBet()).isEqualTo(300);
        assertThat(round.lastFullRaiseSize()).isEqualTo(100);
        assertThat(round.actor().seatIndex()).isEqualTo(3);
        assertThat(round.legalActions().types()).contains(CALL, RAISE);

        round = round.apply(PlayerAction.call());

        assertThat(round.actor().seatIndex()).isZero();
        assertThat(round.legalActions().types()).contains(CALL).doesNotContain(RAISE);
    }

    @Test
    void allInCallContributesOnlyTheRemainingStackAndSkipsAllInSeats() {
        BettingRound round = round(
                0,
                100,
                seat(0, 75, 25),
                allInSeat(1, 60),
                seat(2, 900, 100));

        round = round.apply(PlayerAction.allIn(100));

        assertThat(round.currentBet()).isEqualTo(100);
        assertThat(round.seats().get(0).streetCommitted()).isEqualTo(100);
        assertThat(round.seats().get(0).status()).isEqualTo(ALL_IN);
        assertThat(round.actor().seatIndex()).isEqualTo(2);
    }

    @Test
    void actorSelectionWrapsClockwiseAndSkipsFoldedAllInAndOutSeats() {
        BettingRound round = round(
                5,
                100,
                statusSeat(0, 100, FOLDED),
                allInSeat(2, 0),
                statusSeat(3, 0, OUT),
                seat(4, 100, 0),
                seat(5, 100, 0));

        round = round.apply(PlayerAction.check());

        assertThat(round.actor().seatIndex()).isEqualTo(4);
        round = round.apply(PlayerAction.check());
        assertThat(round.isComplete()).isTrue();
    }

    @Test
    void foldingDownToOnePlayerCompletesImmediatelyAndRejectsFurtherActions() {
        BettingRound round = round(0, 50, seat(0, 100, 0), seat(3, 50, 50));

        BettingRound complete = round.apply(PlayerAction.fold());

        assertThat(complete.isComplete()).isTrue();
        assertThat(complete.actor()).isNull();
        assertThat(complete.seats().get(0).status()).isEqualTo(FOLDED);
        assertThatThrownBy(() -> complete.apply(PlayerAction.check()))
                .isInstanceOf(IllegalActionException.class);
    }

    @Test
    void startRejectsInvalidTableSizeFirstActorAndRaiseSize() {
        List<SeatState> oneSeat = List.of(seat(0, 100, 0));
        List<SeatState> sevenSeats = List.of(
                seat(0, 100, 0),
                seat(1, 100, 0),
                seat(2, 100, 0),
                seat(3, 100, 0),
                seat(4, 100, 0),
                seat(5, 100, 0),
                new SeatState(new PlayerId(new UUID(0, 99)), 5, 100, 0, 0, ACTIVE));
        List<SeatState> inactiveFirst = List.of(
                statusSeat(0, 100, FOLDED), seat(1, 100, 0));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> BettingRound.start(oneSeat, Street.FLOP, 0, 100));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> BettingRound.start(sevenSeats, Street.FLOP, 0, 100));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> BettingRound.start(inactiveFirst, Street.FLOP, 0, 100));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> BettingRound.start(
                        List.of(seat(0, 100, 0), seat(1, 100, 0)),
                        Street.FLOP,
                        2,
                        100));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> BettingRound.start(
                        List.of(seat(0, 100, 0), seat(1, 100, 0)),
                        Street.FLOP,
                        0,
                        0));
        assertThatNullPointerException()
                .isThrownBy(() -> BettingRound.start(null, Street.FLOP, 0, 100));
        assertThatNullPointerException()
                .isThrownBy(() -> BettingRound.start(oneSeat, null, 0, 100));
    }

    @Test
    void startRejectsDuplicateSeatIndexesAndPlayerIds() {
        SeatState first = seat(0, 100, 0);
        SeatState duplicateSeat = new SeatState(
                new PlayerId(new UUID(0, 99)), 0, 100, 0, 0, ACTIVE);
        SeatState duplicatePlayer = new SeatState(
                first.playerId(), 1, 100, 0, 0, ACTIVE);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> round(0, 100, first, duplicateSeat));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> round(0, 100, first, duplicatePlayer));
    }

    @Test
    void startDefensivelyCopiesSeatsWhilePreservingTheirOrderAndDerivingTheCurrentBet() {
        SeatState seatThree = seat(3, 700, 300);
        SeatState seatZero = seat(0, 900, 100);
        ArrayList<SeatState> source = new ArrayList<>(List.of(seatThree, seatZero));

        BettingRound round = BettingRound.start(source, Street.TURN, 3, 200);
        source.clear();

        assertThat(round.seats()).containsExactly(seatThree, seatZero);
        assertThat(round.currentBet()).isEqualTo(300);
        assertThat(round.lastFullRaiseSize()).isEqualTo(200);
        assertThat(round.street()).isEqualTo(Street.TURN);
        assertThat(round.actor()).isEqualTo(seatThree);
        assertThatThrownBy(() -> round.seats().add(seat(1, 100, 0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void actionTypeAbsentFromLegalActionsIsRejectedWithoutChangingTheRound() {
        BettingRound round = preflopRoundWithBlinds(50, 100);
        List<SeatState> originalSeats = round.seats();
        SeatState originalActor = round.actor();

        assertThatThrownBy(() -> round.apply(PlayerAction.check()))
                .isInstanceOf(IllegalActionException.class);

        assertThat(round.seats()).isSameAs(originalSeats);
        assertThat(round.actor()).isSameAs(originalActor);
        assertThat(round.currentBet()).isEqualTo(100);
    }

    @Test
    void aSuccessfulActionReturnsANewRoundWithoutChangingTheOriginal() {
        BettingRound original = preflopRoundWithBlinds(50, 100);

        BettingRound updated = original.apply(PlayerAction.call());

        assertThat(updated).isNotSameAs(original);
        assertThat(original.actor().seatIndex()).isZero();
        assertThat(original.seats().get(0).streetCommitted()).isZero();
        assertThat(updated.actor().seatIndex()).isEqualTo(1);
        assertThat(updated.seats().get(0).streetCommitted()).isEqualTo(100);
    }

    @Test
    void raiseTargetsOutsideTheAdvertisedRangeAreRejectedWithoutChangingTheRound() {
        BettingRound round = preflopRoundWithBlinds(50, 100);

        assertThat(round.legalActions().minRaiseTo()).hasValue(200);
        assertThat(round.legalActions().maxRaiseTo()).isEqualTo(1_000);
        assertThatThrownBy(() -> round.apply(PlayerAction.raiseTo(199)))
                .isInstanceOf(IllegalActionException.class);
        assertThatThrownBy(() -> round.apply(PlayerAction.raiseTo(1_001)))
                .isInstanceOf(IllegalActionException.class);
        assertThat(round.actor().seatIndex()).isZero();
        assertThat(round.seats().get(0).streetCommitted()).isZero();
    }

    @Test
    void allInRequiresTheAdvertisedFullStackTargetAndRejectsAStaleTarget() {
        BettingRound round = round(
                0,
                100,
                seat(0, 400, 0),
                seat(1, 950, 50),
                seat(2, 900, 100));
        PlayerAction staleAllIn = PlayerAction.allIn(round.legalActions().maxRaiseTo());

        assertThatThrownBy(() -> round.apply(PlayerAction.allIn(399)))
                .isInstanceOf(IllegalActionException.class);
        BettingRound nextActor = round.apply(PlayerAction.call());
        assertThatThrownBy(() -> nextActor.apply(staleAllIn))
                .isInstanceOf(IllegalActionException.class);

        assertThat(round.actor().seatIndex()).isZero();
        assertThat(round.seats().get(0).streetCommitted()).isZero();
        assertThat(nextActor.actor().seatIndex()).isEqualTo(1);
        assertThat(nextActor.seats().get(1).streetCommitted()).isEqualTo(50);
    }

    @Test
    void shortStackCallUsesTheAdvertisedCallAmountAndMarksTheActorAllIn() {
        BettingRound round = round(
                0,
                100,
                seat(0, 50, 25),
                seat(1, 900, 100),
                seat(2, 900, 100));

        round = round.apply(PlayerAction.call());

        assertThat(round.seats().get(0).streetCommitted()).isEqualTo(75);
        assertThat(round.seats().get(0).status()).isEqualTo(ALL_IN);
        assertThat(round.currentBet()).isEqualTo(100);
        assertThat(round.actor().seatIndex()).isEqualTo(1);
    }

    @Test
    void allInCallBelowTheCurrentBetDoesNotLowerTheCurrentBet() {
        BettingRound round = round(
                0,
                100,
                seat(0, 50, 25),
                seat(1, 900, 100),
                seat(2, 900, 100));

        round = round.apply(PlayerAction.allIn(75));

        assertThat(round.seats().get(0).streetCommitted()).isEqualTo(75);
        assertThat(round.seats().get(0).status()).isEqualTo(ALL_IN);
        assertThat(round.currentBet()).isEqualTo(100);
        assertThat(round.lastFullRaiseSize()).isEqualTo(100);
    }

    @Test
    void theLastActionablePlayerCanRespondAndCompleteAgainstAllInPlayers() {
        BettingRound round = round(
                4,
                100,
                allInSeat(0, 100),
                allInSeat(2, 100),
                seat(4, 500, 100));

        BettingRound complete = round.apply(PlayerAction.check());

        assertThat(complete.isComplete()).isTrue();
        assertThat(complete.actor()).isNull();
    }

    @Test
    void exactLongMaximumAllInRaiseDoesNotWrapAndAllowsItsFinalCall() {
        BettingRound round = round(
                0,
                1,
                new SeatState(
                        new PlayerId(new UUID(0, 1)),
                        0,
                        1,
                        Long.MAX_VALUE - 1,
                        Long.MAX_VALUE - 1,
                        ACTIVE),
                new SeatState(
                        new PlayerId(new UUID(0, 2)),
                        1,
                        1,
                        Long.MAX_VALUE - 1,
                        Long.MAX_VALUE - 1,
                        ACTIVE));

        BettingRound atMaximum = round.apply(PlayerAction.allIn(Long.MAX_VALUE));

        assertThat(atMaximum.currentBet()).isEqualTo(Long.MAX_VALUE);
        assertThat(atMaximum.lastFullRaiseSize()).isEqualTo(1);
        assertThat(atMaximum.actor().seatIndex()).isEqualTo(1);

        BettingRound complete = atMaximum.apply(PlayerAction.call());

        assertThat(complete.isComplete()).isTrue();
        assertThat(complete.seats().get(1).streetCommitted()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void restoreRejectsInProgressRoundThatWouldAlreadyBeCompleteAfterFolds() {
        SeatState actor = seat(0, 100, 0);
        SeatState folded = statusSeat(1, 100, FOLDED);
        BettingRoundCheckpoint checkpoint = new BettingRoundCheckpoint(
                List.of(actor, folded),
                Street.PREFLOP,
                0,
                100,
                Set.of(actor.playerId()),
                Set.of(actor.playerId()),
                actor.playerId());

        assertThatThrownBy(() -> BettingRound.restore(checkpoint))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete");
    }

    private static BettingRound preflopRoundWithBlinds(long smallBlind, long bigBlind) {
        return round(
                0,
                bigBlind,
                seat(0, 1_000, 0),
                seat(1, 1_000 - smallBlind, smallBlind),
                seat(2, 1_000 - bigBlind, bigBlind));
    }

    private static BettingRound round(
            int firstToActSeat, long lastFullRaiseSize, SeatState... seats) {
        return BettingRound.start(
                List.of(seats), Street.PREFLOP, firstToActSeat, lastFullRaiseSize);
    }

    private static SeatState seat(int seatIndex, long stack, long committed) {
        PlayerId playerId = new PlayerId(new UUID(0, seatIndex + 1L));
        return new SeatState(playerId, seatIndex, stack, committed, committed, ACTIVE);
    }

    private static SeatState allInSeat(int seatIndex, long committed) {
        return statusSeat(seatIndex, 0, committed, ALL_IN);
    }

    private static SeatState statusSeat(int seatIndex, long stack, PlayerStatus status) {
        return statusSeat(seatIndex, stack, 0, status);
    }

    private static SeatState statusSeat(
            int seatIndex, long stack, long committed, PlayerStatus status) {
        PlayerId playerId = new PlayerId(new UUID(0, seatIndex + 1L));
        return new SeatState(playerId, seatIndex, stack, committed, committed, status);
    }
}
