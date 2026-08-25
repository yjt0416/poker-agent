package com.agenttavern.game.handvalue;

import static com.agenttavern.game.handvalue.HandCategory.FLUSH;
import static com.agenttavern.game.handvalue.HandCategory.FOUR_OF_A_KIND;
import static com.agenttavern.game.handvalue.HandCategory.FULL_HOUSE;
import static com.agenttavern.game.handvalue.HandCategory.HIGH_CARD;
import static com.agenttavern.game.handvalue.HandCategory.ONE_PAIR;
import static com.agenttavern.game.handvalue.HandCategory.STRAIGHT;
import static com.agenttavern.game.handvalue.HandCategory.STRAIGHT_FLUSH;
import static com.agenttavern.game.handvalue.HandCategory.THREE_OF_A_KIND;
import static com.agenttavern.game.handvalue.HandCategory.TWO_PAIR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.agenttavern.game.card.Card;
import com.agenttavern.game.card.Rank;
import com.agenttavern.game.card.Suit;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class HandEvaluatorTest {

    @ParameterizedTest
    @MethodSource("categoryExamples")
    void classifiesEveryCategory(String notation, HandCategory expected) {
        assertThat(HandEvaluator.evaluate(cards(notation)).category()).isEqualTo(expected);
    }

    static Stream<Arguments> categoryExamples() {
        return Stream.of(
                arguments("As Kd 9c 6h 3s", HIGH_CARD),
                arguments("As Ad 9c 6h 3s", ONE_PAIR),
                arguments("As Ad 9c 9h 3s", TWO_PAIR),
                arguments("As Ad Ac 6h 3s", THREE_OF_A_KIND),
                arguments("9s 8d 7c 6h 5s", STRAIGHT),
                arguments("As Js 9s 6s 3s", FLUSH),
                arguments("As Ad Ac 9h 9s", FULL_HOUSE),
                arguments("As Ad Ac Ah 3s", FOUR_OF_A_KIND),
                arguments("9s 8s 7s 6s 5s", STRAIGHT_FLUSH));
    }

    @ParameterizedTest
    @MethodSource("tieBreakerExamples")
    void producesExactTieBreakerVector(
            String notation, HandCategory expectedCategory, List<Integer> expectedTieBreakers) {
        assertThat(HandEvaluator.evaluate(cards(notation)))
                .isEqualTo(new HandValue(expectedCategory, expectedTieBreakers));
    }

    static Stream<Arguments> tieBreakerExamples() {
        return Stream.of(
                arguments("As Kd 9c 6h 3s", HIGH_CARD, List.of(14, 13, 9, 6, 3)),
                arguments("As Ad 9c 6h 3s", ONE_PAIR, List.of(14, 9, 6, 3)),
                arguments("As Ad 9c 9h 3s", TWO_PAIR, List.of(14, 9, 3)),
                arguments("As Ad Ac 6h 3s", THREE_OF_A_KIND, List.of(14, 6, 3)),
                arguments("9s 8d 7c 6h 5s", STRAIGHT, List.of(9)),
                arguments("As Js 9s 6s 3s", FLUSH, List.of(14, 11, 9, 6, 3)),
                arguments("As Ad Ac 9h 9s", FULL_HOUSE, List.of(14, 9)),
                arguments("As Ad Ac Ah 3s", FOUR_OF_A_KIND, List.of(14, 3)),
                arguments("9s 8s 7s 6s 5s", STRAIGHT_FLUSH, List.of(9)));
    }

    @Test
    void comparesPairsByHighestDifferingKicker() {
        HandValue kingKicker = HandEvaluator.evaluate(cards("As Ad Kc 7h 3s"));
        HandValue queenKicker = HandEvaluator.evaluate(cards("Ah Ac Qc 7d 3c"));

        assertThat(kingKicker).isGreaterThan(queenKicker);
    }

    @Test
    void categoryOutranksTieBreakers() {
        assertThat(new HandValue(STRAIGHT, List.of(5)))
                .isGreaterThan(new HandValue(THREE_OF_A_KIND, List.of(14, 13, 12)));
    }

    @Test
    void evaluatesWheelAsFiveHighStraight() {
        assertThat(HandEvaluator.evaluate(cards("As 2d 3c 4h 5s")))
                .isEqualTo(new HandValue(STRAIGHT, List.of(5)));
    }

    @Test
    void wheelLosesToSixHighStraight() {
        HandValue wheel = HandEvaluator.evaluate(cards("As 2d 3c 4h 5s"));
        HandValue sixHigh = HandEvaluator.evaluate(cards("2s 3d 4c 5h 6s"));

        assertThat(wheel).isLessThan(sixHigh);
    }

    @Test
    void selectsAceHighStraightFlushFromSevenCards() {
        assertThat(HandEvaluator.evaluate(cards("As Ks Qs Js Ts 2d 2c")))
                .isEqualTo(new HandValue(STRAIGHT_FLUSH, List.of(14)));
    }

    @Test
    void selectsBestFiveWhenTheyAreNotFirst() {
        assertThat(HandEvaluator.evaluate(cards("2d 2c As Ks Qs Js Ts")))
                .isEqualTo(new HandValue(STRAIGHT_FLUSH, List.of(14)));
    }

    @Test
    void rejectsDuplicateCards() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HandEvaluator.evaluate(cards("As As Qs Js Ts")));
    }

    @ParameterizedTest
    @MethodSource("invalidSizedHands")
    void rejectsHandsOutsideFiveToSevenCards(String notation) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HandEvaluator.evaluate(cards(notation)));
    }

    static Stream<String> invalidSizedHands() {
        return Stream.of("As Ks Qs Js", "As Ks Qs Js Ts 9d 8c 7h");
    }

    @Test
    void rejectsNullCardList() {
        assertThatIllegalArgumentException().isThrownBy(() -> HandEvaluator.evaluate(null));
    }

    @Test
    void rejectsNullCard() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HandEvaluator.evaluate(
                        Arrays.asList(card("As"), card("Ks"), card("Qs"), card("Js"), null)));
    }

    @Test
    void handValueRejectsNullCategory() {
        assertThatNullPointerException()
                .isThrownBy(() -> new HandValue(null, List.of(14)))
                .withMessage("category");
    }

    @Test
    void handValueRejectsNullTieBreakers() {
        assertThatNullPointerException()
                .isThrownBy(() -> new HandValue(HIGH_CARD, null))
                .withMessage("tieBreakers");
    }

    @Test
    void handValueRejectsEmptyTieBreakers() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new HandValue(HIGH_CARD, List.of()))
                .withMessage("tieBreakers must not be empty");
    }

    @ParameterizedTest
    @MethodSource("outOfRangeTieBreakers")
    void handValueRejectsTieBreakersOutsidePokerRanks(int tieBreaker) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new HandValue(HIGH_CARD, List.of(tieBreaker)))
                .withMessage("tieBreakers must contain only ranks from 2 to 14");
    }

    static Stream<Integer> outOfRangeTieBreakers() {
        return Stream.of(1, 15);
    }

    @Test
    void handValueRejectsNullTieBreaker() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new HandValue(HIGH_CARD, Arrays.asList(14, null)))
                .withMessage("tieBreakers must contain only ranks from 2 to 14");
    }

    @Test
    void sameCategoryComparisonRejectsMismatchedTieBreakerLengths() {
        HandValue left = new HandValue(ONE_PAIR, List.of(14, 13, 12, 11));
        HandValue right = new HandValue(ONE_PAIR, List.of(14, 13, 12));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> left.compareTo(right))
                .withMessage("Cannot compare hand values with different tie-breaker lengths");
    }

    private static List<Card> cards(String notation) {
        return Arrays.stream(notation.split(" ")).map(HandEvaluatorTest::card).toList();
    }

    private static Card card(String notation) {
        return new Card(suit(notation.charAt(1)), rank(notation.charAt(0)));
    }

    private static Rank rank(char notation) {
        return switch (notation) {
            case '2' -> Rank.TWO;
            case '3' -> Rank.THREE;
            case '4' -> Rank.FOUR;
            case '5' -> Rank.FIVE;
            case '6' -> Rank.SIX;
            case '7' -> Rank.SEVEN;
            case '8' -> Rank.EIGHT;
            case '9' -> Rank.NINE;
            case 'T' -> Rank.TEN;
            case 'J' -> Rank.JACK;
            case 'Q' -> Rank.QUEEN;
            case 'K' -> Rank.KING;
            case 'A' -> Rank.ACE;
            default -> throw new IllegalArgumentException("Unknown rank: " + notation);
        };
    }

    private static Suit suit(char notation) {
        return switch (notation) {
            case 'c' -> Suit.CLUBS;
            case 'd' -> Suit.DIAMONDS;
            case 'h' -> Suit.HEARTS;
            case 's' -> Suit.SPADES;
            default -> throw new IllegalArgumentException("Unknown suit: " + notation);
        };
    }
}
