package com.agenttavern.game.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeckTest {

    @Test
    void standardDeckContainsExactlyFiftyTwoUniqueCards() {
        Deck deck = Deck.standard();
        Set<Card> drawn = new HashSet<>();

        while (deck.remaining() > 0) {
            Deck.Draw draw = deck.draw();
            assertThat(drawn.add(draw.card())).isTrue();
            deck = draw.deck();
        }

        assertThat(drawn).hasSize(52);
    }

    @Test
    void stackedDeckDrawsInDeclaredOrderWithoutMutatingOriginal() {
        Card ace = new Card(Suit.SPADES, Rank.ACE);
        Card king = new Card(Suit.HEARTS, Rank.KING);
        Deck deck = Deck.ordered(List.of(ace, king));

        Deck.Draw draw = deck.draw();

        assertThat(draw.card()).isEqualTo(ace);
        assertThat(draw.deck().draw().card()).isEqualTo(king);
        assertThat(deck.remaining()).isEqualTo(2);
    }

    @Test
    void orderedDeckRejectsDuplicateCards() {
        Card ace = new Card(Suit.SPADES, Rank.ACE);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> Deck.ordered(List.of(ace, new Card(Suit.SPADES, Rank.ACE))))
                .withMessageContaining("duplicate");
    }

    @Test
    void orderedDeckRejectsNullCardList() {
        assertThatNullPointerException()
                .isThrownBy(() -> Deck.ordered(null))
                .withMessage("cards");
    }

    @Test
    void orderedDeckRejectsNullCard() {
        List<Card> cards = new ArrayList<>();
        cards.add(null);

        assertThatNullPointerException()
                .isThrownBy(() -> Deck.ordered(cards))
                .withMessage("card");
    }

    @Test
    void orderedDeckKeepsAnImmutableCopyOfItsInput() {
        Card ace = new Card(Suit.SPADES, Rank.ACE);
        List<Card> cards = new ArrayList<>(List.of(ace));
        Deck deck = Deck.ordered(cards);
        cards.clear();

        assertThat(deck.remaining()).isEqualTo(1);
        assertThat(deck.draw().card()).isEqualTo(ace);
    }

    @Test
    void shuffleWithSameSeedProducesSameNonStandardDrawSequence() {
        List<Card> first = drawAll(Deck.standard().shuffled(new Random(8128)));
        List<Card> second = drawAll(Deck.standard().shuffled(new Random(8128)));
        List<Card> standard = drawAll(Deck.standard());

        assertThat(first).isEqualTo(second);
        assertThat(first).isNotEqualTo(standard);
    }

    @Test
    void shuffleDoesNotMutateTheOriginalDeck() {
        Deck deck = Deck.standard();

        assertThatNoException().isThrownBy(() -> deck.shuffled(new Random(8128)));
        assertThat(deck.draw().card()).isEqualTo(new Card(Suit.CLUBS, Rank.TWO));
    }

    @Test
    void drawRejectsAnEmptyDeck() {
        Deck emptyDeck = Deck.ordered(List.of());

        assertThatThrownBy(emptyDeck::draw)
                .isInstanceOfSatisfying(
                        NoSuchElementException.class,
                        exception -> assertThat(exception).hasMessageContaining("empty"));
    }

    private List<Card> drawAll(Deck deck) {
        List<Card> cards = new ArrayList<>();
        while (deck.remaining() > 0) {
            Deck.Draw draw = deck.draw();
            cards.add(draw.card());
            deck = draw.deck();
        }
        return cards;
    }
}
