package com.agenttavern.game.showdown;

import static com.agenttavern.game.betting.PlayerStatus.ACTIVE;
import static com.agenttavern.game.betting.PlayerStatus.FOLDED;
import static com.agenttavern.game.card.Rank.ACE;
import static com.agenttavern.game.card.Rank.EIGHT;
import static com.agenttavern.game.card.Rank.FIVE;
import static com.agenttavern.game.card.Rank.FOUR;
import static com.agenttavern.game.card.Rank.JACK;
import static com.agenttavern.game.card.Rank.KING;
import static com.agenttavern.game.card.Rank.QUEEN;
import static com.agenttavern.game.card.Rank.SIX;
import static com.agenttavern.game.card.Rank.TEN;
import static com.agenttavern.game.card.Rank.THREE;
import static com.agenttavern.game.card.Rank.TWO;
import static com.agenttavern.game.card.Suit.CLUBS;
import static com.agenttavern.game.card.Suit.DIAMONDS;
import static com.agenttavern.game.card.Suit.HEARTS;
import static com.agenttavern.game.card.Suit.SPADES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.betting.SeatState;
import com.agenttavern.game.card.Card;
import com.agenttavern.game.card.Rank;
import com.agenttavern.game.card.Suit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShowdownResolverTest {

    @Test
    void resolvesEachPotAgainstOnlyThatPotsEligiblePlayers() {
        PlayerId aces = player(0);
        PlayerId kings = player(1);
        PlayerId queens = player(2);
        List<Pot> pots = List.of(
                new Pot(300, Set.of(aces, kings, queens)),
                new Pot(200, Set.of(kings, queens)));

        Map<PlayerId, Long> payouts = ShowdownResolver.resolve(
                pots,
                Map.of(
                        aces, cards(ACE, HEARTS, ACE, DIAMONDS),
                        kings, cards(KING, HEARTS, KING, DIAMONDS),
                        queens, cards(QUEEN, HEARTS, QUEEN, DIAMONDS)),
                dryBoard(),
                Map.of(aces, 0, kings, 1, queens, 2),
                5);

        assertThat(payouts).containsExactlyInAnyOrderEntriesOf(Map.of(aces, 300L, kings, 200L));
    }

    @Test
    void givesOddChipToFirstTiedWinnerLeftOfAnEmptyButton() {
        PlayerId seatZero = player(0);
        PlayerId seatFour = player(4);

        Map<PlayerId, Long> payouts = ShowdownResolver.resolve(
                List.of(new Pot(101, Set.of(seatZero, seatFour))),
                Map.of(
                        seatZero, cards(TWO, CLUBS, THREE, DIAMONDS),
                        seatFour, cards(FOUR, CLUBS, FIVE, DIAMONDS)),
                royalFlushBoard(),
                Map.of(seatZero, 0, seatFour, 4),
                2);

        assertThat(payouts).containsExactlyInAnyOrderEntriesOf(
                Map.of(seatZero, 50L, seatFour, 51L));
    }

    @Test
    void oddChipOrderWrapsFromButtonSeatFiveToSeatZero() {
        PlayerId seatZero = player(0);
        PlayerId seatFour = player(4);

        Map<PlayerId, Long> payouts = ShowdownResolver.resolve(
                List.of(new Pot(101, Set.of(seatZero, seatFour))),
                Map.of(
                        seatZero, cards(TWO, CLUBS, THREE, DIAMONDS),
                        seatFour, cards(FOUR, CLUBS, FIVE, DIAMONDS)),
                royalFlushBoard(),
                Map.of(seatZero, 0, seatFour, 4),
                5);

        assertThat(payouts).containsExactlyInAnyOrderEntriesOf(
                Map.of(seatZero, 51L, seatFour, 50L));
    }

    @Test
    void foldedPlayerFundsPotButCannotWinWithTheStrongestCards() {
        PlayerId eligible = player(0);
        PlayerId folded = player(1);
        List<Pot> pots = SidePotCalculator.calculate(List.of(
                new SeatState(eligible, 0, 900, 0, 100, ACTIVE),
                new SeatState(folded, 1, 900, 0, 100, FOLDED)));

        Map<PlayerId, Long> payouts = ShowdownResolver.resolve(
                pots,
                Map.of(
                        eligible, cards(QUEEN, HEARTS, TEN, DIAMONDS),
                        folded, cards(ACE, HEARTS, ACE, DIAMONDS)),
                dryBoard(),
                Map.of(eligible, 0, folded, 1),
                5);

        assertThat(payouts).containsExactly(Map.entry(eligible, 200L));
    }

    @Test
    void rejectsMissingOrExtraPlayerMappings() {
        PlayerId eligible = player(0);
        PlayerId extra = player(1);
        List<Pot> pots = List.of(new Pot(10, Set.of(eligible)));

        assertThatThrownBy(() -> ShowdownResolver.resolve(
                        pots, Map.of(), dryBoard(), Map.of(eligible, 0), 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mapping");
        assertThatThrownBy(() -> ShowdownResolver.resolve(
                        pots,
                        Map.of(eligible, cards(ACE, HEARTS, ACE, DIAMONDS),
                                extra, cards(KING, HEARTS, KING, DIAMONDS)),
                        dryBoard(),
                        Map.of(eligible, 0),
                        5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mapping");
        assertThatThrownBy(() -> ShowdownResolver.resolve(
                        pots,
                        Map.of(eligible, cards(ACE, HEARTS, ACE, DIAMONDS)),
                        dryBoard(),
                        Map.of(),
                        5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eligible");
    }

    @Test
    void rejectsBoardsThatDoNotContainExactlyFiveCards() {
        PlayerId eligible = player(0);
        List<Pot> pots = List.of(new Pot(10, Set.of(eligible)));

        assertThatThrownBy(() -> ShowdownResolver.resolve(
                        pots,
                        Map.of(eligible, cards(ACE, HEARTS, ACE, DIAMONDS)),
                        dryBoard().subList(0, 4),
                        Map.of(eligible, 0),
                        5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("five");
    }

    @Test
    void rejectsIncompleteOrDuplicateHoleCards() {
        PlayerId first = player(0);
        PlayerId second = player(1);
        List<Pot> pots = List.of(new Pot(10, Set.of(first, second)));
        Map<PlayerId, Integer> seats = Map.of(first, 0, second, 1);

        assertThatThrownBy(() -> ShowdownResolver.resolve(
                        pots,
                        Map.of(first, List.of(card(ACE, HEARTS)),
                                second, cards(KING, HEARTS, KING, DIAMONDS)),
                        dryBoard(),
                        seats,
                        5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("two");
        assertThatThrownBy(() -> ShowdownResolver.resolve(
                        pots,
                        Map.of(first, cards(ACE, HEARTS, ACE, HEARTS),
                                second, cards(KING, HEARTS, KING, DIAMONDS)),
                        dryBoard(),
                        seats,
                        5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void rejectsCardsDuplicatedAcrossBoardAndPlayers() {
        PlayerId first = player(0);
        PlayerId second = player(1);
        List<Pot> pots = List.of(new Pot(10, Set.of(first, second)));
        Map<PlayerId, Integer> seats = Map.of(first, 0, second, 1);

        assertThatThrownBy(() -> ShowdownResolver.resolve(
                        pots,
                        Map.of(first, cards(TWO, CLUBS, ACE, HEARTS),
                                second, cards(KING, HEARTS, KING, DIAMONDS)),
                        dryBoard(),
                        seats,
                        5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> ShowdownResolver.resolve(
                        pots,
                        Map.of(first, cards(ACE, HEARTS, ACE, DIAMONDS),
                                second, cards(ACE, HEARTS, KING, DIAMONDS)),
                        dryBoard(),
                        seats,
                        5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void rejectsDuplicateOrOutOfRangeSeatsAndButtons() {
        PlayerId first = player(0);
        PlayerId second = player(1);
        List<Pot> pots = List.of(new Pot(10, Set.of(first, second)));
        Map<PlayerId, List<Card>> holes = Map.of(
                first, cards(ACE, HEARTS, ACE, DIAMONDS),
                second, cards(KING, HEARTS, KING, DIAMONDS));

        assertThatThrownBy(() -> ShowdownResolver.resolve(
                        pots, holes, dryBoard(), Map.of(first, 0, second, 0), 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate seat");
        assertThatThrownBy(() -> ShowdownResolver.resolve(
                        pots, holes, dryBoard(), Map.of(first, 0, second, 6), 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seat");
        assertThatThrownBy(() -> ShowdownResolver.resolve(
                        pots, holes, dryBoard(), Map.of(first, 0, second, 1), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("button");
        assertThatThrownBy(() -> ShowdownResolver.resolve(
                        pots, holes, dryBoard(), Map.of(first, 0, second, 1), 6))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("button");
    }

    @Test
    void rejectsNullStructuresAndNullMembers() {
        PlayerId eligible = player(0);
        List<Pot> pots = List.of(new Pot(10, Set.of(eligible)));
        Map<PlayerId, List<Card>> holes = Map.of(
                eligible, cards(ACE, HEARTS, ACE, DIAMONDS));
        Map<PlayerId, Integer> seats = Map.of(eligible, 0);

        assertThatThrownBy(() -> ShowdownResolver.resolve(null, holes, dryBoard(), seats, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShowdownResolver.resolve(
                        new ArrayList<>(java.util.Arrays.asList((Pot) null)),
                        holes,
                        dryBoard(),
                        seats,
                        5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShowdownResolver.resolve(pots, null, dryBoard(), seats, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShowdownResolver.resolve(pots, holes, null, seats, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShowdownResolver.resolve(pots, holes, dryBoard(), null, 5))
                .isInstanceOf(IllegalArgumentException.class);

        Map<PlayerId, Integer> nullSeat = new HashMap<>();
        nullSeat.put(eligible, null);
        assertThatThrownBy(() -> ShowdownResolver.resolve(
                        pots, holes, dryBoard(), nullSeat, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seat");
    }

    @Test
    void rejectsPotTotalsThatOverflowLongRange() {
        PlayerId eligible = player(0);

        assertThatThrownBy(() -> ShowdownResolver.resolve(
                        List.of(
                                new Pot(Long.MAX_VALUE, Set.of(eligible)),
                                new Pot(1, Set.of(eligible))),
                        Map.of(eligible, cards(ACE, HEARTS, ACE, DIAMONDS)),
                        dryBoard(),
                        Map.of(eligible, 0),
                        5))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void returnsAnImmutableMapContainingOnlyPaidPlayers() {
        PlayerId winner = player(0);
        PlayerId loser = player(1);

        Map<PlayerId, Long> payouts = ShowdownResolver.resolve(
                List.of(new Pot(10, Set.of(winner, loser))),
                Map.of(
                        winner, cards(ACE, HEARTS, ACE, DIAMONDS),
                        loser, cards(KING, HEARTS, KING, DIAMONDS)),
                dryBoard(),
                Map.of(winner, 0, loser, 1),
                5);

        assertThat(payouts).containsExactly(Map.entry(winner, 10L));
        assertThatThrownBy(() -> payouts.put(loser, 1L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyPotListReturnsNoPayouts() {
        Map<PlayerId, Long> payouts = ShowdownResolver.resolve(
                List.of(), Map.of(), dryBoard(), Map.of(), 5);

        assertThat(payouts).isEmpty();
        assertThatThrownBy(() -> payouts.put(player(0), 1L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static List<Card> dryBoard() {
        return List.of(
                card(TWO, CLUBS),
                card(FOUR, DIAMONDS),
                card(SIX, HEARTS),
                card(EIGHT, SPADES),
                card(JACK, CLUBS));
    }

    private static List<Card> royalFlushBoard() {
        return List.of(
                card(TEN, HEARTS),
                card(JACK, HEARTS),
                card(QUEEN, HEARTS),
                card(KING, HEARTS),
                card(ACE, HEARTS));
    }

    private static List<Card> cards(Rank firstRank, Suit firstSuit, Rank secondRank, Suit secondSuit) {
        return List.of(card(firstRank, firstSuit), card(secondRank, secondSuit));
    }

    private static Card card(Rank rank, Suit suit) {
        return new Card(suit, rank);
    }

    private static PlayerId player(int index) {
        return new PlayerId(new UUID(0, index + 1L));
    }
}
