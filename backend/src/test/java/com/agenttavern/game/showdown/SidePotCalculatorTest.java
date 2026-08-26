package com.agenttavern.game.showdown;

import static com.agenttavern.game.betting.PlayerStatus.ACTIVE;
import static com.agenttavern.game.betting.PlayerStatus.ALL_IN;
import static com.agenttavern.game.betting.PlayerStatus.FOLDED;
import static com.agenttavern.game.betting.PlayerStatus.OUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.betting.PlayerStatus;
import com.agenttavern.game.betting.SeatState;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SidePotCalculatorTest {

    @Test
    void buildsMainAndTwoSidePotsFromThreeContributionLevels() {
        List<SeatState> seats = List.of(
                committed(0, 100, ALL_IN),
                committed(1, 300, ALL_IN),
                committed(2, 500, ACTIVE),
                committed(3, 500, FOLDED));

        assertThat(SidePotCalculator.calculate(seats)).containsExactly(
                pot(400, 0, 1, 2),
                pot(600, 1, 2),
                pot(400, 2));
    }

    @Test
    void equalContributionsBuildOnePotAndFoldedPlayersOnlyFundIt() {
        List<SeatState> seats = List.of(
                committed(0, 250, ALL_IN),
                committed(1, 250, ACTIVE),
                committed(2, 250, FOLDED));

        assertThat(SidePotCalculator.calculate(seats)).containsExactly(pot(750, 0, 1));
    }

    @Test
    void outPlayerFundsPotButIsNeverEligible() {
        List<SeatState> seats = List.of(
                committed(0, 250, ACTIVE),
                committed(1, 250, OUT));

        assertThat(SidePotCalculator.calculate(seats)).containsExactly(pot(500, 0));
    }

    @Test
    void zeroContributionsProduceNoPots() {
        List<SeatState> seats = List.of(
                committed(0, 0, ACTIVE),
                committed(1, 0, FOLDED));

        assertThat(SidePotCalculator.calculate(seats)).isEmpty();
    }

    @Test
    void rejectsAContributionLayerWithNoEligiblePlayer() {
        List<SeatState> seats = List.of(
                committed(0, 100, ACTIVE),
                committed(1, 300, FOLDED));

        assertThatThrownBy(() -> SidePotCalculator.calculate(seats))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eligible");
    }

    @Test
    void rejectsDuplicatePlayersAndDuplicateSeatIndexes() {
        PlayerId duplicatedPlayer = player(0);
        SeatState first = new SeatState(duplicatedPlayer, 0, 0, 0, 100, ALL_IN);
        SeatState duplicatePlayer = new SeatState(duplicatedPlayer, 1, 0, 0, 100, ALL_IN);
        SeatState duplicateSeat = new SeatState(player(1), 0, 0, 0, 100, ALL_IN);

        assertThatThrownBy(() -> SidePotCalculator.calculate(List.of(first, duplicatePlayer)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("player");
        assertThatThrownBy(() -> SidePotCalculator.calculate(List.of(first, duplicateSeat)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seat");
    }

    @Test
    void rejectsNullInputAndNullSeats() {
        assertThatThrownBy(() -> SidePotCalculator.calculate(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SidePotCalculator.calculate(java.util.Arrays.asList((SeatState) null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void detectsOverflowWhenLayerAmountsExceedLongRange() {
        List<SeatState> seats = List.of(
                committed(0, Long.MAX_VALUE, ALL_IN),
                committed(1, Long.MAX_VALUE, ALL_IN));

        assertThatThrownBy(() -> SidePotCalculator.calculate(seats))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void potRequiresPositiveAmountAndEligiblePlayers() {
        assertThatThrownBy(() -> new Pot(0, Set.of(player(0))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Pot(-1, Set.of(player(0))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Pot(1, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Pot(1, null))
                .isInstanceOf(IllegalArgumentException.class);

        Set<PlayerId> containingNull = new HashSet<>();
        containingNull.add(null);
        assertThatThrownBy(() -> new Pot(1, containingNull))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void potDefensivelyCopiesItsEligiblePlayers() {
        Set<PlayerId> mutablePlayers = new LinkedHashSet<>();
        mutablePlayers.add(player(0));
        Pot pot = new Pot(10, mutablePlayers);

        mutablePlayers.add(player(1));

        assertThat(pot.eligiblePlayers()).containsExactly(player(0));
        assertThatThrownBy(() -> pot.eligiblePlayers().add(player(2)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void potCopiesEligiblePlayersInPlayerIdValueOrder() {
        Set<PlayerId> players = new LinkedHashSet<>(List.of(player(5), player(1), player(3)));

        Pot pot = new Pot(10, players);

        assertThat(pot.eligiblePlayers()).containsExactly(player(1), player(3), player(5));
    }

    @Test
    void potAcceptsSixEligiblePlayers() {
        Pot pot = new Pot(60, Set.of(
                player(0), player(1), player(2), player(3), player(4), player(5)));

        assertThat(pot.amount()).isEqualTo(60);
        assertThat(pot.eligiblePlayers()).hasSize(6);
    }

    @Test
    void potRejectsSevenEligiblePlayers() {
        Set<PlayerId> sevenPlayers = Set.of(
                player(0),
                player(1),
                player(2),
                player(3),
                player(4),
                player(5),
                player(6));

        assertThatThrownBy(() -> new Pot(70, sevenPlayers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 6");
    }

    private static SeatState committed(int seatIndex, long amount, PlayerStatus status) {
        long stack = status == ALL_IN || status == OUT ? 0 : 1_000;
        return new SeatState(player(seatIndex), seatIndex, stack, 0, amount, status);
    }

    private static Pot pot(long amount, int... seatIndexes) {
        Set<PlayerId> players = java.util.Arrays.stream(seatIndexes)
                .mapToObj(SidePotCalculatorTest::player)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new Pot(amount, players);
    }

    private static PlayerId player(int seatIndex) {
        return new PlayerId(new UUID(0, seatIndex + 1L));
    }
}
