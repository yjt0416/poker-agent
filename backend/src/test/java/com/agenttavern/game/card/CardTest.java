package com.agenttavern.game.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class CardTest {

    @Test
    void cardRejectsNullSuit() {
        assertThatNullPointerException()
                .isThrownBy(() -> new Card(null, Rank.ACE))
                .withMessage("suit");
    }

    @Test
    void cardRejectsNullRank() {
        assertThatNullPointerException()
                .isThrownBy(() -> new Card(Suit.SPADES, null))
                .withMessage("rank");
    }

    @Test
    void ranksHavePokerStrengthsFromTwoThroughAce() {
        assertThat(Rank.TWO.strength()).isEqualTo(2);
        assertThat(Rank.ACE.strength()).isEqualTo(14);
    }
}
