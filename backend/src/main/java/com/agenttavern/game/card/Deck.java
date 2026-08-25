package com.agenttavern.game.card;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.random.RandomGenerator;

public final class Deck {
    private final List<Card> cards;

    private Deck(List<Card> cards) {
        this.cards = List.copyOf(cards);
    }

    public static Deck ordered(List<Card> cards) {
        Objects.requireNonNull(cards, "cards");

        HashSet<Card> uniqueCards = new HashSet<>();
        for (Card card : cards) {
            Objects.requireNonNull(card, "card");
            if (!uniqueCards.add(card)) {
                throw new IllegalArgumentException("duplicate card: " + card);
            }
        }
        return new Deck(cards);
    }

    public static Deck standard() {
        List<Card> cards = new ArrayList<>(Suit.values().length * Rank.values().length);
        for (Suit suit : Suit.values()) {
            for (Rank rank : Rank.values()) {
                cards.add(new Card(suit, rank));
            }
        }
        return new Deck(cards);
    }

    public Deck shuffled(RandomGenerator random) {
        Objects.requireNonNull(random, "random");

        List<Card> shuffledCards = new ArrayList<>(cards);
        for (int index = shuffledCards.size() - 1; index > 0; index--) {
            int swapIndex = random.nextInt(index + 1);
            Card card = shuffledCards.get(index);
            shuffledCards.set(index, shuffledCards.get(swapIndex));
            shuffledCards.set(swapIndex, card);
        }
        return new Deck(shuffledCards);
    }

    public Draw draw() {
        if (cards.isEmpty()) {
            throw new NoSuchElementException("cannot draw from an empty deck");
        }
        return new Draw(cards.getFirst(), new Deck(cards.subList(1, cards.size())));
    }

    public int remaining() {
        return cards.size();
    }

    public record Draw(Card card, Deck deck) {}
}
