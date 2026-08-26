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
import static org.assertj.core.api.Assertions.assertThatNoException;
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
    void terminalSeatsCannotContribute() {
        for (PlayerStatus status : new PlayerStatus[] {FOLDED, PlayerStatus.ALL_IN, OUT}) {
            SeatState seat = terminalSeat(status);
            long chips = status == FOLDED ? seat.stack() : 0;

            assertThatThrownBy(() -> seat.withContribution(chips))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("withContribution requires ACTIVE status, was " + status);
        }
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
    void terminalSeatsCannotFold() {
        for (PlayerStatus status : new PlayerStatus[] {FOLDED, PlayerStatus.ALL_IN, OUT}) {
            SeatState seat = terminalSeat(status);

            assertThatThrownBy(seat::fold)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("fold requires ACTIVE status, was " + status);
        }
    }

    @Test
    void streetResetPreservesEveryPlayerStatus() {
        for (PlayerStatus status : PlayerStatus.values()) {
            SeatState source = status == ACTIVE
                    ? new SeatState(playerId(), 0, 100, 25, 50, ACTIVE)
                    : terminalSeat(status);

            SeatState reset = source.resetStreet();

            assertThat(reset.status()).as("status %s", status).isEqualTo(status);
            assertThat(reset.stack()).as("status %s", status).isEqualTo(source.stack());
            assertThat(reset.streetCommitted()).as("status %s", status).isZero();
            assertThat(reset.handCommitted()).as("status %s", status).isEqualTo(50);
        }
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
    void settlementIsTheHandBoundaryForEveryPlayerStatus() {
        for (PlayerStatus status : PlayerStatus.values()) {
            SeatState source = status == ACTIVE
                    ? new SeatState(playerId(), 0, 100, 25, 50, ACTIVE)
                    : terminalSeat(status);

            SeatState zeroPayout = source.settled(0);
            SeatState winningPayout = source.settled(25);
            long expectedZeroPayoutStack = status == ACTIVE || status == FOLDED ? 100 : 0;
            long expectedWinningStack = status == ACTIVE || status == FOLDED ? 125 : 25;
            PlayerStatus expectedZeroPayoutStatus = expectedZeroPayoutStack > 0 ? ACTIVE : OUT;

            assertThat(zeroPayout.stack())
                    .as("zero payout stack for %s", status)
                    .isEqualTo(expectedZeroPayoutStack);
            assertThat(zeroPayout.status())
                    .as("zero payout status for %s", status)
                    .isEqualTo(expectedZeroPayoutStatus);
            assertThat(winningPayout.stack())
                    .as("winning stack for %s", status)
                    .isEqualTo(expectedWinningStack);
            assertThat(winningPayout.status())
                    .as("winning status for %s", status)
                    .isEqualTo(ACTIVE);
            assertThat(zeroPayout.streetCommitted()).isZero();
            assertThat(zeroPayout.handCommitted()).isZero();
            assertThat(winningPayout.streetCommitted()).isZero();
            assertThat(winningPayout.handCommitted()).isZero();
        }
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
    void inactiveActorIsFilteredBeforeItsUnusedAllInTargetCanOverflow() {
        SeatState folded = new SeatState(
                playerId(), 0, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, FOLDED);

        assertThatNoException()
                .isThrownBy(() -> LegalActions.calculate(folded, 0, 1, true));
        LegalActions legal = LegalActions.calculate(folded, 0, 1, true);

        assertThat(legal)
                .isEqualTo(new LegalActions(Set.of(), 0, OptionalLong.empty(), 0));
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
    void activeZeroStackFacingABetCanFoldButCannotCallZeroChips() {
        SeatState actor = seat(0, 0, 0, ACTIVE);

        assertThatNoException()
                .isThrownBy(() -> LegalActions.calculate(actor, 100, 100, true));
        LegalActions legal = LegalActions.calculate(actor, 100, 100, true);

        assertThat(legal.types()).containsExactly(FOLD);
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
    void callMembershipMustMatchAPositiveCallAmount() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LegalActions(Set.of(CALL), 0, OptionalLong.empty(), 100))
                .withMessage("CALL must be present exactly when callAmount is positive");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LegalActions(Set.of(FOLD), 25, OptionalLong.empty(), 100))
                .withMessage("CALL must be present exactly when callAmount is positive");
    }

    @Test
    void raiseMembershipMustMatchAPresentMinimumTarget() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LegalActions(Set.of(RAISE), 0, OptionalLong.empty(), 100))
                .withMessage("RAISE must be present exactly when minRaiseTo is present");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LegalActions(Set.of(CHECK), 0, OptionalLong.of(50), 100))
                .withMessage("RAISE must be present exactly when minRaiseTo is present");
    }

    @Test
    void minimumRaiseTargetCannotExceedMaximumTarget() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LegalActions(Set.of(RAISE), 0, OptionalLong.of(101), 100))
                .withMessage("minRaiseTo cannot exceed maxRaiseTo");
    }

    @Test
    void callAmountCannotExceedMaximumTarget() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LegalActions(Set.of(CALL), 101, OptionalLong.empty(), 100))
                .withMessage("callAmount cannot exceed maxRaiseTo");
    }

    @Test
    void advertisedAllInRequiresAPositiveTarget() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LegalActions(Set.of(ALL_IN), 0, OptionalLong.empty(), 0))
                .withMessage("ALL_IN requires a positive maxRaiseTo target");
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
    void legalActionCalculationRejectsAnUnrepresentableActorAllInTarget() {
        SeatState maxTargetOverflow = new SeatState(
                playerId(), 0, 1, Long.MAX_VALUE, Long.MAX_VALUE, ACTIVE);

        assertThatThrownBy(() -> LegalActions.calculate(
                        maxTargetOverflow, Long.MAX_VALUE, 1, true))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void unrepresentableFullRaiseMinimumKeepsPassiveActionsAndAllInAvailable() {
        SeatState actor = seat(0, Long.MAX_VALUE, 0, ACTIVE);

        LegalActions legal = LegalActions.calculate(actor, Long.MAX_VALUE, 1, true);

        assertThat(legal.types()).containsExactlyInAnyOrder(FOLD, CALL, ALL_IN);
        assertThat(legal.callAmount()).isEqualTo(Long.MAX_VALUE);
        assertThat(legal.minRaiseTo()).isEmpty();
        assertThat(legal.maxRaiseTo()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void activeActorRejectsZeroOrPositiveCurrentBetBelowItsCommitment() {
        SeatState actor = seat(0, 100, 100, ACTIVE);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> LegalActions.calculate(actor, 0, 50, true))
                .withMessage("currentBet cannot be below actor.streetCommitted");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LegalActions.calculate(actor, 50, 50, true))
                .withMessage("currentBet cannot be below actor.streetCommitted");
    }

    @Test
    void currentBetEqualToCommitmentKeepsCheckAndForwardRaiseTargets() {
        SeatState actor = seat(0, 100, 100, ACTIVE);

        LegalActions legal = LegalActions.calculate(actor, 100, 50, true);

        assertThat(legal.types()).containsExactlyInAnyOrder(CHECK, RAISE, ALL_IN);
        assertThat(legal.callAmount()).isZero();
        assertThat(legal.minRaiseTo()).hasValue(150);
        assertThat(legal.maxRaiseTo()).isEqualTo(200);
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

    private static SeatState terminalSeat(PlayerStatus status) {
        long stack = status == FOLDED ? 100 : 0;
        return new SeatState(playerId(), 0, stack, 25, 50, status);
    }
}
