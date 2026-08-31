package com.agenttavern.game.hand;

import static com.agenttavern.game.card.Rank.ACE;
import static com.agenttavern.game.card.Rank.EIGHT;
import static com.agenttavern.game.card.Rank.FIVE;
import static com.agenttavern.game.card.Rank.FOUR;
import static com.agenttavern.game.card.Rank.KING;
import static com.agenttavern.game.card.Rank.NINE;
import static com.agenttavern.game.card.Rank.QUEEN;
import static com.agenttavern.game.card.Rank.SEVEN;
import static com.agenttavern.game.card.Rank.SIX;
import static com.agenttavern.game.card.Rank.THREE;
import static com.agenttavern.game.card.Rank.TWO;
import static com.agenttavern.game.card.Suit.CLUBS;
import static com.agenttavern.game.card.Suit.DIAMONDS;
import static com.agenttavern.game.card.Suit.HEARTS;
import static com.agenttavern.game.card.Suit.SPADES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenttavern.game.betting.BettingRoundCheckpoint;
import com.agenttavern.game.betting.PlayerAction;
import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.betting.SeatState;
import com.agenttavern.game.card.Card;
import com.agenttavern.game.card.Deck;
import com.agenttavern.game.card.DeckCheckpoint;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HandCheckpointTest {

    private static final BlindLevel BLINDS = new BlindLevel(50, 100);

    @Test
    void restoredHandProducesTheSameNextTransition() {
        Hand original = handAfterPreflopRaiseAndCall();
        Hand restored = Hand.restore(original.checkpoint());

        assertThat(restored.checkpoint()).isEqualTo(original.checkpoint());
        PlayerId actor = original.actor().playerId();
        PlayerAction action = PlayerAction.call();
        HandTransition originalTransition = original.act(actor, action);
        HandTransition restoredTransition = restored.act(actor, action);

        assertThat(restoredTransition.events()).isEqualTo(originalTransition.events());
        assertThat(restoredTransition.hand().checkpoint())
                .isEqualTo(originalTransition.hand().checkpoint());
    }

    @Test
    void checkpointPreservesRemainingDeckOrder() {
        assertDeckOrderAfterRestore();
    }

    @Test
    void publicEventsNeverContainDeckOrBurnedCards() {
        Hand hand = handAfterPreflopRaiseAndCall();

        assertNoSensitiveComponents(hand.act(hand.actor().playerId(), PlayerAction.call()).events());
    }

    @Test
    void completedHandCanBeRestored() {
        assertCompletedRoundTrip();
    }

    @Test
    void checkpointDefensivelyCopiesNestedCollections() {
        assertDeeplyImmutable();
    }

    @Test
    void restoreRejectsDuplicatePhysicalCards() {
        assertDuplicateCardRejected();
    }

    @Test
    void restoreRejectsActorOutsidePendingActiveSeats() {
        assertInvalidActorRejected();
    }

    @Test
    void restoreRejectsChipConservationViolation() {
        assertChipMismatchRejected();
    }

    private static void assertDeckOrderAfterRestore() {
        Hand original = handAfterPreflopRaiseAndCall();
        Hand restored = Hand.restore(original.checkpoint());
        PlayerId actor = original.actor().playerId();

        HandTransition originalTransition = original.act(actor, PlayerAction.call());
        HandTransition restoredTransition = restored.act(actor, PlayerAction.call());

        assertThat(restoredTransition.hand().checkpoint().deck().remainingCards())
                .containsExactlyElementsOf(originalTransition.hand().checkpoint().deck().remainingCards());
        assertThat(restoredTransition.events()).isEqualTo(originalTransition.events());
    }

    private static void assertNoSensitiveComponents(List<HandEvent> events) {
        for (HandEvent event : events) {
            for (RecordComponent component : event.getClass().getRecordComponents()) {
                assertThat(component.getType()).isNotEqualTo(Deck.class);
                assertThat(component.getType()).isNotEqualTo(DeckCheckpoint.class);
                assertThat(component.getName()).doesNotContainIgnoringCase("burn");
            }
        }
    }

    private static void assertCompletedRoundTrip() {
        Hand completed = Hand.start(
                        hand(20),
                        List.of(player(21, 0, 50), player(22, 1, 100)),
                        0,
                        BLINDS,
                        Deck.ordered(fixedCardsForHeadsUp()))
                .hand();

        Hand restored = Hand.restore(completed.checkpoint());

        assertThat(restored.checkpoint()).isEqualTo(completed.checkpoint());
        assertThat(restored.isComplete()).isTrue();
        assertThat(restored.actor()).isNull();
    }

    private static void assertDeeplyImmutable() {
        HandCheckpoint source = handAfterPreflopRaiseAndCall().checkpoint();
        List<Card> deckCards = new ArrayList<>(source.deck().remainingCards());
        List<SeatState> seats = new ArrayList<>(source.seats());
        Map<PlayerId, List<Card>> holeCards = mutableHoleCards(source.holeCards());
        List<Card> board = new ArrayList<>(source.board());
        List<Card> burnedCards = new ArrayList<>(source.burnedCards());
        BettingRoundCheckpoint round = source.bettingRound().orElseThrow();
        List<SeatState> roundSeats = new ArrayList<>(round.seats());
        Set<PlayerId> pending = new LinkedHashSet<>(round.pendingAction());
        Set<PlayerId> raiseRights = new LinkedHashSet<>(round.raiseRights());
        BettingRoundCheckpoint copiedRound = new BettingRoundCheckpoint(
                roundSeats,
                round.street(),
                round.currentBet(),
                round.lastFullRaiseSize(),
                pending,
                raiseRights,
                round.actorId());
        HandCheckpoint checkpoint = new HandCheckpoint(
                source.id(),
                source.buttonSeat(),
                source.blinds(),
                new DeckCheckpoint(deckCards),
                seats,
                holeCards,
                board,
                burnedCards,
                java.util.Optional.of(copiedRound),
                source.street(),
                source.complete(),
                source.initialTotalChips());

        deckCards.clear();
        seats.clear();
        holeCards.clear();
        board.clear();
        burnedCards.clear();
        roundSeats.clear();
        pending.clear();
        raiseRights.clear();

        assertThat(checkpoint.deck().remainingCards()).isNotEmpty();
        assertThat(checkpoint.seats()).isNotEmpty();
        assertThat(checkpoint.holeCards()).isNotEmpty();
        assertThat(checkpoint.board()).isEmpty();
        assertThat(checkpoint.burnedCards()).isEmpty();
        assertThat(checkpoint.bettingRound().orElseThrow().seats()).isNotEmpty();
        assertThat(checkpoint.bettingRound().orElseThrow().pendingAction()).isNotEmpty();
        assertThatThrownBy(() -> checkpoint.deck().remainingCards().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> checkpoint.seats().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> checkpoint.holeCards().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> checkpoint.holeCards().values().iterator().next().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> checkpoint.bettingRound().orElseThrow().seats().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> checkpoint.bettingRound().orElseThrow().pendingAction().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static void assertDuplicateCardRejected() {
        HandCheckpoint source = handAfterPreflopRaiseAndCall().checkpoint();
        List<Card> remainingCards = new ArrayList<>(source.deck().remainingCards());
        remainingCards.set(0, source.holeCards().values().iterator().next().getFirst());
        HandCheckpoint duplicate = checkpointWith(
                source,
                new DeckCheckpoint(remainingCards),
                source.seats(),
                source.bettingRound());

        assertThatThrownBy(() -> Hand.restore(duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    private static void assertInvalidActorRejected() {
        HandCheckpoint source = handAfterPreflopRaiseAndCall().checkpoint();
        BettingRoundCheckpoint round = source.bettingRound().orElseThrow();
        BettingRoundCheckpoint invalidActor = new BettingRoundCheckpoint(
                round.seats(),
                round.street(),
                round.currentBet(),
                round.lastFullRaiseSize(),
                round.pendingAction(),
                round.raiseRights(),
                new PlayerId(new UUID(0, 99)));
        HandCheckpoint invalid = checkpointWith(
                source, source.deck(), source.seats(), java.util.Optional.of(invalidActor));

        assertThatThrownBy(() -> Hand.restore(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actor");
    }

    private static void assertChipMismatchRejected() {
        HandCheckpoint source = handAfterPreflopRaiseAndCall().checkpoint();
        List<SeatState> changedSeats = new ArrayList<>(source.seats());
        SeatState first = changedSeats.getFirst();
        changedSeats.set(0, new SeatState(
                first.playerId(),
                first.seatIndex(),
                first.stack() + 1,
                first.streetCommitted(),
                first.handCommitted(),
                first.status()));
        BettingRoundCheckpoint round = source.bettingRound().orElseThrow();
        BettingRoundCheckpoint changedRound = new BettingRoundCheckpoint(
                changedSeats,
                round.street(),
                round.currentBet(),
                round.lastFullRaiseSize(),
                round.pendingAction(),
                round.raiseRights(),
                round.actorId());
        HandCheckpoint invalid = checkpointWith(
                source, source.deck(), changedSeats, java.util.Optional.of(changedRound));

        assertThatThrownBy(() -> Hand.restore(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conserve");
    }

    private static HandCheckpoint checkpointWith(
            HandCheckpoint source,
            DeckCheckpoint deck,
            List<SeatState> seats,
            java.util.Optional<BettingRoundCheckpoint> bettingRound) {
        return new HandCheckpoint(
                source.id(),
                source.buttonSeat(),
                source.blinds(),
                deck,
                seats,
                source.holeCards(),
                source.board(),
                source.burnedCards(),
                bettingRound,
                source.street(),
                source.complete(),
                source.initialTotalChips());
    }

    private static Map<PlayerId, List<Card>> mutableHoleCards(Map<PlayerId, List<Card>> source) {
        Map<PlayerId, List<Card>> copy = new LinkedHashMap<>();
        source.forEach((playerId, cards) -> copy.put(playerId, new ArrayList<>(cards)));
        return copy;
    }

    private static Hand handAfterPreflopRaiseAndCall() {
        PlayerStack button = player(1, 5, 1_000);
        PlayerStack smallBlind = player(2, 0, 1_000);
        PlayerStack bigBlind = player(3, 2, 1_000);
        Hand hand = Hand.start(
                        hand(1),
                        List.of(button, smallBlind, bigBlind),
                        5,
                        BLINDS,
                        Deck.ordered(fixedCardsForThreePlayers()))
                .hand();
        hand = hand.act(button.playerId(), PlayerAction.raiseTo(200)).hand();
        return hand.act(smallBlind.playerId(), PlayerAction.call()).hand();
    }

    private static List<Card> fixedCardsForThreePlayers() {
        return List.of(
                card(ACE, SPADES),
                card(KING, SPADES),
                card(QUEEN, SPADES),
                card(ACE, HEARTS),
                card(KING, HEARTS),
                card(QUEEN, HEARTS),
                card(TWO, CLUBS),
                card(THREE, CLUBS),
                card(FOUR, CLUBS),
                card(FIVE, CLUBS),
                card(SIX, DIAMONDS),
                card(SEVEN, DIAMONDS),
                card(EIGHT, HEARTS),
                card(NINE, HEARTS));
    }

    private static List<Card> fixedCardsForHeadsUp() {
        return List.of(
                card(ACE, SPADES),
                card(KING, SPADES),
                card(ACE, HEARTS),
                card(KING, HEARTS),
                card(TWO, CLUBS),
                card(THREE, CLUBS),
                card(FOUR, CLUBS),
                card(FIVE, CLUBS),
                card(SIX, DIAMONDS),
                card(SEVEN, DIAMONDS),
                card(EIGHT, HEARTS),
                card(NINE, HEARTS));
    }

    private static PlayerStack player(long id, int seat, long chips) {
        return new PlayerStack(new PlayerId(new UUID(0, id)), seat, chips);
    }

    private static HandId hand(long id) {
        return new HandId(new UUID(1, id));
    }

    private static Card card(com.agenttavern.game.card.Rank rank, com.agenttavern.game.card.Suit suit) {
        return new Card(suit, rank);
    }
}
