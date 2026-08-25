package com.agenttavern.game.betting;

import static com.agenttavern.game.betting.ActionType.ALL_IN;
import static com.agenttavern.game.betting.ActionType.CALL;
import static com.agenttavern.game.betting.ActionType.CHECK;
import static com.agenttavern.game.betting.ActionType.FOLD;
import static com.agenttavern.game.betting.ActionType.RAISE;
import static com.agenttavern.game.betting.PlayerStatus.ACTIVE;
import static com.agenttavern.game.betting.PlayerStatus.FOLDED;
import static com.agenttavern.game.betting.PlayerStatus.OUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LegalActionsTest {

    @Test
    void facingNoBetAllowsCheckAndARaiseAtLeastOneBigBlind() {
        SeatState actor = seat(0, 1_000, 0, ACTIVE);

        LegalActions legal = LegalActions.calculate(actor, 0, 100, true);

        assertThat(legal.types()).containsExactlyInAnyOrder(CHECK, RAISE, ALL_IN);
        assertThat(legal.callAmount()).isZero();
        assertThat(legal.minRaiseTo()).hasValue(100);
        assertThat(legal.maxRaiseTo()).isEqualTo(1_000);
    }

    @Test
    void facingBetCapsCallAtRemainingStackAndOffersShortAllIn() {
        SeatState actor = seat(0, 75, 25, ACTIVE);

        LegalActions legal = LegalActions.calculate(actor, 200, 100, true);

        assertThat(legal.types()).containsExactlyInAnyOrder(FOLD, CALL, ALL_IN);
        assertThat(legal.callAmount()).isEqualTo(75);
        assertThat(legal.minRaiseTo()).isEmpty();
        assertThat(legal.maxRaiseTo()).isEqualTo(100);
    }

    @Test
    void playerIdRejectsNullAndRandomProducesAnIdentity() {
        assertThatNullPointerException().isThrownBy(() -> new PlayerId(null));
        assertThat(PlayerId.random().value()).isNotNull();
    }

    @Test
    void seatRejectsNullIdentityAndStatus() {
        PlayerId playerId = playerId();

        assertThatNullPointerException()
                .isThrownBy(() -> new SeatState(null, 0, 100, 0, 0, ACTIVE));
        assertThatNullPointerException()
                .isThrownBy(() -> new SeatState(playerId, 0, 100, 0, 0, null));
    }

    @Test
    void seatIndexMustBeBetweenZeroAndFive() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SeatState(playerId(), -1, 100, 0, 0, ACTIVE));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SeatState(playerId(), 6, 100, 0, 0, ACTIVE));
    }

    @Test
    void seatRejectsNegativeChipFields() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SeatState(playerId(), 0, -1, 0, 0, ACTIVE));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SeatState(playerId(), 0, 0, -1, 0, ACTIVE));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SeatState(playerId(), 0, 0, 0, -1, ACTIVE));
    }

    @Test
    void streetContributionCannotExceedHandContribution() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SeatState(playerId(), 0, 100, 51, 50, ACTIVE));
    }

    @Test
    void allInAndOutSeatsMustHaveZeroStack() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SeatState(playerId(), 0, 1, 0, 0, PlayerStatus.ALL_IN));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SeatState(playerId(), 0, 1, 0, 0, OUT));
    }

    @Test
    void contributionMovesChipsFromStackIntoStreetAndHandTotals() {
        SeatState actor = new SeatState(playerId(), 2, 100, 20, 50, ACTIVE);

        SeatState contributed = actor.withContribution(30);

        assertThat(contributed)
                .isEqualTo(new SeatState(actor.playerId(), 2, 70, 50, 80, ACTIVE));
        assertThat(actor.stack()).isEqualTo(100);
    }

    @Test
    void contributingTheEntireStackMarksTheSeatAllIn() {
        SeatState actor = new SeatState(playerId(), 2, 30, 20, 50, ACTIVE);

        SeatState contributed = actor.withContribution(30);

        assertThat(contributed)
                .isEqualTo(new SeatState(actor.playerId(), 2, 0, 50, 80, PlayerStatus.ALL_IN));
    }

    @Test
    void contributionMustBeNonNegativeAndWithinTheStack() {
        SeatState actor = new SeatState(playerId(), 2, 30, 20, 50, ACTIVE);

        assertThatIllegalArgumentException().isThrownBy(() -> actor.withContribution(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> actor.withContribution(31));
    }

    @Test
    void contributionRejectsCommitmentOverflow() {
        SeatState actor = new SeatState(
                playerId(), 2, 1, Long.MAX_VALUE, Long.MAX_VALUE, ACTIVE);

        assertThatThrownBy(() -> actor.withContribution(1))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void foldAndStreetResetReturnNewStatesWithoutChangingChipOwnership() {
        SeatState actor = new SeatState(playerId(), 2, 100, 20, 50, ACTIVE);

        SeatState folded = actor.fold();
        SeatState reset = folded.resetStreet();

        assertThat(folded.status()).isEqualTo(FOLDED);
        assertThat(folded.stack()).isEqualTo(100);
        assertThat(folded.streetCommitted()).isEqualTo(20);
        assertThat(reset.streetCommitted()).isZero();
        assertThat(reset.handCommitted()).isEqualTo(50);
        assertThat(reset.status()).isEqualTo(FOLDED);
    }

    @Test
    void settlementClearsContributionsAndRestoresASeatWithChips() {
        SeatState actor = new SeatState(playerId(), 2, 10, 20, 50, FOLDED);

        SeatState settled = actor.settled(40);

        assertThat(settled)
                .isEqualTo(new SeatState(actor.playerId(), 2, 50, 0, 0, ACTIVE));
    }

    @Test
    void settlementMarksASeatWithNoChipsOut() {
        SeatState actor = new SeatState(playerId(), 2, 0, 20, 50, PlayerStatus.ALL_IN);

        assertThat(actor.settled(0))
                .isEqualTo(new SeatState(actor.playerId(), 2, 0, 0, 0, OUT));
    }

    @Test
    void settlementRejectsNegativePayoutAndStackOverflow() {
        SeatState actor = new SeatState(playerId(), 2, 10, 0, 0, ACTIVE);
        SeatState enormous = new SeatState(playerId(), 2, Long.MAX_VALUE, 0, 0, ACTIVE);

        assertThatIllegalArgumentException().isThrownBy(() -> actor.settled(-1));
        assertThatThrownBy(() -> enormous.settled(1)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void actionFactoriesUseZeroForPassiveActionsAndTargetsForAggressiveActions() {
        assertThat(PlayerAction.fold()).isEqualTo(new PlayerAction(FOLD, 0));
        assertThat(PlayerAction.check()).isEqualTo(new PlayerAction(CHECK, 0));
        assertThat(PlayerAction.call()).isEqualTo(new PlayerAction(CALL, 0));
        assertThat(PlayerAction.raiseTo(250)).isEqualTo(new PlayerAction(RAISE, 250));
        assertThat(PlayerAction.allIn(475)).isEqualTo(new PlayerAction(ActionType.ALL_IN, 475));
    }

    @Test
    void passiveActionsRejectNonZeroAmounts() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PlayerAction(FOLD, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new PlayerAction(CHECK, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> new PlayerAction(CALL, 1));
    }

    @Test
    void raiseAndAllInTargetsMustBePositive() {
        assertThatIllegalArgumentException().isThrownBy(() -> PlayerAction.raiseTo(0));
        assertThatIllegalArgumentException().isThrownBy(() -> PlayerAction.raiseTo(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> PlayerAction.allIn(0));
        assertThatIllegalArgumentException().isThrownBy(() -> PlayerAction.allIn(-1));
    }

    @Test
    void actionRejectsNullType() {
        assertThatNullPointerException().isThrownBy(() -> new PlayerAction(null, 0));
    }

    @Test
    void closedRaiseRightsSuppressRaiseButKeepCallAndAllInBoundaries() {
        SeatState actor = seat(0, 1_000, 100, ACTIVE);

        LegalActions legal = LegalActions.calculate(actor, 200, 100, false);

        assertThat(legal.types()).containsExactlyInAnyOrder(FOLD, CALL, ALL_IN);
        assertThat(legal.callAmount()).isEqualTo(100);
        assertThat(legal.minRaiseTo()).isEmpty();
        assertThat(legal.maxRaiseTo()).isEqualTo(1_100);
    }

    @Test
    void exactStackCallOffersCallAndAllInAtTheSameTarget() {
        SeatState actor = seat(0, 75, 25, ACTIVE);

        LegalActions legal = LegalActions.calculate(actor, 100, 100, true);

        assertThat(legal.types()).containsExactlyInAnyOrder(FOLD, CALL, ALL_IN);
        assertThat(legal.callAmount()).isEqualTo(75);
        assertThat(legal.minRaiseTo()).isEmpty();
        assertThat(legal.maxRaiseTo()).isEqualTo(100);
    }

    @Test
    void stackThatReachesTheMinimumExactlyOffersAFullAllInRaise() {
        SeatState actor = seat(0, 250, 50, ACTIVE);

        LegalActions legal = LegalActions.calculate(actor, 100, 200, true);

        assertThat(legal.types()).containsExactlyInAnyOrder(FOLD, CALL, RAISE, ALL_IN);
        assertThat(legal.callAmount()).isEqualTo(50);
        assertThat(legal.minRaiseTo()).hasValue(300);
        assertThat(legal.maxRaiseTo()).isEqualTo(300);
    }

    @Test
    void inactiveActorsReceiveNoActions() {
        for (PlayerStatus status : Set.of(FOLDED, PlayerStatus.ALL_IN, OUT)) {
            long stack = status == FOLDED ? 100 : 0;
            SeatState actor = seat(0, stack, 25, status);

            LegalActions legal = LegalActions.calculate(actor, 100, 50, true);

            assertThat(legal.types()).as("status %s", status).isEmpty();
            assertThat(legal.callAmount()).as("status %s", status).isZero();
            assertThat(legal.minRaiseTo()).as("status %s", status).isEmpty();
        }
    }

    @Test
    void activeZeroStackCanCheckButCannotRaiseOrGoAllIn() {
        SeatState actor = seat(0, 0, 0, ACTIVE);

        LegalActions legal = LegalActions.calculate(actor, 0, 100, true);

        assertThat(legal.types()).containsExactly(CHECK);
        assertThat(legal.callAmount()).isZero();
        assertThat(legal.minRaiseTo()).isEmpty();
        assertThat(legal.maxRaiseTo()).isZero();
    }

    @Test
    void legalActionTypesAreDefensivelyCopiedAndUnmodifiable() {
        EnumSet<ActionType> source = EnumSet.of(CHECK, ALL_IN);
        LegalActions legal = new LegalActions(source, 0, OptionalLong.empty(), 100);

        source.add(FOLD);

        assertThat(legal.types()).containsExactlyInAnyOrder(CHECK, ALL_IN);
        assertThatThrownBy(() -> legal.types().add(FOLD))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void legalActionsRejectNullComponents() {
        assertThatNullPointerException()
                .isThrownBy(() -> new LegalActions(null, 0, OptionalLong.empty(), 0));
        assertThatNullPointerException()
                .isThrownBy(() -> new LegalActions(Set.of(CHECK), 0, null, 0));
        assertThatNullPointerException()
                .isThrownBy(() -> new LegalActions(
                        java.util.Collections.singleton(null), 0, OptionalLong.empty(), 0));
        assertThatNullPointerException()
                .isThrownBy(() -> LegalActions.calculate(null, 0, 100, true));
    }

    @Test
    void legalActionsRejectNegativeAmountsAndNonPositiveRaiseSize() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LegalActions(Set.of(CALL), -1, OptionalLong.empty(), 100));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LegalActions(Set.of(CHECK), 0, OptionalLong.empty(), -1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LegalActions(Set.of(RAISE), 0, OptionalLong.of(-1), 100));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LegalActions.calculate(seat(0, 100, 0, ACTIVE), -1, 100, true));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LegalActions.calculate(seat(0, 100, 0, ACTIVE), 0, -1, true));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LegalActions.calculate(seat(0, 100, 0, ACTIVE), 0, 0, true));
    }

    @Test
    void legalActionCalculationRejectsTargetOverflow() {
        SeatState maxTargetOverflow = new SeatState(
                playerId(), 0, 1, Long.MAX_VALUE, Long.MAX_VALUE, ACTIVE);
        SeatState minTargetOverflow = new SeatState(
                playerId(), 0, Long.MAX_VALUE, 0, 0, ACTIVE);

        assertThatThrownBy(() -> LegalActions.calculate(maxTargetOverflow, 0, 1, true))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> LegalActions.calculate(
                        minTargetOverflow, Long.MAX_VALUE, 1, true))
                .isInstanceOf(ArithmeticException.class);
    }

    private static SeatState seat(
            int seatIndex, long stack, long streetCommitted, PlayerStatus status) {
        return new SeatState(
                new PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                seatIndex,
                stack,
                streetCommitted,
                streetCommitted,
                status);
    }

    private static PlayerId playerId() {
        return new PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }
}
