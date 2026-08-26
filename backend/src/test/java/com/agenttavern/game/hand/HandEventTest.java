package com.agenttavern.game.hand;

import static com.agenttavern.game.betting.PlayerStatus.ACTIVE;
import static com.agenttavern.game.betting.Street.FLOP;
import static com.agenttavern.game.betting.Street.TURN;
import static com.agenttavern.game.card.Rank.ACE;
import static com.agenttavern.game.card.Rank.KING;
import static com.agenttavern.game.card.Rank.QUEEN;
import static com.agenttavern.game.card.Suit.CLUBS;
import static com.agenttavern.game.card.Suit.HEARTS;
import static com.agenttavern.game.card.Suit.SPADES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.betting.SeatState;
import com.agenttavern.game.card.Card;
import com.agenttavern.game.showdown.Pot;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HandEventTest {

    @Test
    void rejectsStructurallyIncompleteReplayPayloads() {
        HandId handId = hand(1);
        PlayerId first = player(1);
        PlayerId second = player(2);
        Card ace = card(ACE, SPADES);

        assertThatThrownBy(() -> new HandEvent.HoleCardsDealt(
                        handId, Map.of(first, List.of(ace))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HandEvent.CommunityCardsDealt(
                        handId, FLOP, List.of(ace)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HandEvent.CommunityCardsDealt(
                        handId, TURN, List.of(ace, card(KING, HEARTS))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HandEvent.BlindsPosted(
                        handId, first, 0, 0, second, 1, 100))
                .isInstanceOf(IllegalArgumentException.class);

        Pot pot = new Pot(10, Set.of(first, second));
        assertThatThrownBy(() -> new HandEvent.PotsAwarded(
                        handId, List.of(pot), Map.of(first, 9L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HandEvent.PotsAwarded(
                        handId,
                        List.of(new Pot(100, Set.of(first)), new Pot(100, Set.of(second))),
                        Map.of(first, 200L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HandEvent.HandCompleted(
                        handId,
                        List.of(
                                new SeatState(first, 0, 99, 0, 1, ACTIVE),
                                new SeatState(second, 1, 100, 0, 0, ACTIVE))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPayoutsThatCannotFitTheJointCapacityOfTheirEligiblePots() {
        HandId handId = hand(2);
        PlayerId first = player(1);
        PlayerId second = player(2);
        PlayerId third = player(3);

        assertThatThrownBy(() -> new HandEvent.PotsAwarded(
                        handId,
                        List.of(
                                new Pot(100, Set.of(first, second)),
                                new Pot(100, Set.of(first, second)),
                                new Pot(100, Set.of(third))),
                        Map.of(first, 150L, second, 150L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPotsAwardedAcrossMoreThanSixEligiblePlayers() {
        List<PlayerId> players = java.util.stream.LongStream.rangeClosed(1, 7)
                .mapToObj(HandEventTest::player)
                .toList();

        assertThatThrownBy(() -> new HandEvent.PotsAwarded(
                        hand(3),
                        List.of(
                                new Pot(60, Set.copyOf(players.subList(0, 6))),
                                new Pot(10, Set.of(players.get(6)))),
                        Map.of(players.get(0), 60L, players.get(6), 10L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void potsAwardedKeepsNestedEligibilityInPlayerIdOrder() {
        PlayerId first = player(30);
        PlayerId second = player(10);
        PlayerId third = player(20);

        HandEvent.PotsAwarded awarded = new HandEvent.PotsAwarded(
                hand(4),
                List.of(new Pot(60, Set.of(first, second, third))),
                Map.of(second, 60L));

        assertThat(awarded.pots().getFirst().eligiblePlayers())
                .containsExactly(second, third, first);
    }

    @Test
    void everyEventAndTransitionDefensivelyCopiesMutableCollections() {
        HandId handId = hand(2);
        PlayerId first = player(3);
        PlayerId second = player(4);
        PlayerStack firstStack = new PlayerStack(first, 0, 100);
        PlayerStack secondStack = new PlayerStack(second, 1, 100);
        List<PlayerStack> players = new ArrayList<>(List.of(firstStack, secondStack));
        HandEvent.HandStarted started =
                new HandEvent.HandStarted(handId, players, 0, new BlindLevel(1, 2));
        players.clear();

        List<Card> firstCards = new ArrayList<>(List.of(
                card(ACE, SPADES), card(ACE, HEARTS)));
        Map<PlayerId, List<Card>> holes = new LinkedHashMap<>();
        holes.put(first, firstCards);
        holes.put(second, List.of(card(KING, SPADES), card(KING, HEARTS)));
        HandEvent.HoleCardsDealt dealt = new HandEvent.HoleCardsDealt(handId, holes);
        firstCards.clear();
        holes.clear();

        List<Card> community = new ArrayList<>(List.of(
                card(QUEEN, CLUBS), card(KING, CLUBS), card(ACE, CLUBS)));
        HandEvent.CommunityCardsDealt flop =
                new HandEvent.CommunityCardsDealt(handId, FLOP, community);
        community.clear();

        Pot pot = new Pot(10, Set.of(first, second));
        List<Pot> pots = new ArrayList<>(List.of(pot));
        Map<PlayerId, Long> payouts = new LinkedHashMap<>();
        payouts.put(first, 10L);
        HandEvent.PotsAwarded awarded = new HandEvent.PotsAwarded(handId, pots, payouts);
        pots.clear();
        payouts.clear();

        List<SeatState> finalSeats = new ArrayList<>(List.of(
                new SeatState(first, 0, 110, 0, 0, ACTIVE),
                new SeatState(second, 1, 90, 0, 0, ACTIVE)));
        HandEvent.HandCompleted completed = new HandEvent.HandCompleted(handId, finalSeats);
        finalSeats.clear();
        List<HandEvent> sourceEvents = new ArrayList<>(List.of(started, dealt, flop, awarded, completed));

        HandTransition transition = new HandTransition(minimalHand(), sourceEvents);
        sourceEvents.clear();

        assertThat(started.players()).containsExactly(firstStack, secondStack);
        assertThat(dealt.holeCards().get(first))
                .containsExactly(card(ACE, SPADES), card(ACE, HEARTS));
        assertThat(flop.cards()).hasSize(3);
        assertThat(awarded.pots()).containsExactly(pot);
        assertThat(awarded.payouts()).containsExactly(Map.entry(first, 10L));
        assertThat(completed.seats()).hasSize(2);
        assertThat(transition.events()).hasSize(5);
        assertThatThrownBy(() -> started.players().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> dealt.holeCards().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> dealt.holeCards().get(first).clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> flop.cards().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> awarded.pots().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> awarded.payouts().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> completed.seats().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> transition.events().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sealedEventPayloadsContainOnlyStableDomainAndJdkTypes() {
        assertThat(HandEvent.class.isSealed()).isTrue();
        assertThat(HandEvent.class.getPermittedSubclasses()).containsExactlyInAnyOrder(
                HandEvent.HandStarted.class,
                HandEvent.HoleCardsDealt.class,
                HandEvent.BlindsPosted.class,
                HandEvent.PlayerActed.class,
                HandEvent.CommunityCardsDealt.class,
                HandEvent.PotsAwarded.class,
                HandEvent.HandCompleted.class);

        for (Class<?> eventType : HandEvent.class.getPermittedSubclasses()) {
            assertThat(eventType.isRecord()).isTrue();
            for (RecordComponent component : eventType.getRecordComponents()) {
                assertThat(component.getName().toLowerCase())
                        .doesNotContain("apikey", "api_key", "prompt", "framework");
                String packageName = component.getType().getPackageName();
                assertThat(packageName)
                        .satisfiesAnyOf(
                                name -> assertThat(name).startsWith("com.agenttavern.game"),
                                name -> assertThat(name).startsWith("java.lang"),
                                name -> assertThat(name).startsWith("java.util"));
            }
        }
    }

    private static Hand minimalHand() {
        PlayerStack first = new PlayerStack(player(11), 0, 100);
        PlayerStack second = new PlayerStack(player(12), 1, 100);
        List<Card> deck = List.of(
                card(ACE, SPADES),
                card(KING, SPADES),
                card(ACE, HEARTS),
                card(KING, HEARTS),
                new Card(com.agenttavern.game.card.Suit.DIAMONDS, com.agenttavern.game.card.Rank.TWO),
                new Card(com.agenttavern.game.card.Suit.DIAMONDS, com.agenttavern.game.card.Rank.THREE),
                new Card(com.agenttavern.game.card.Suit.DIAMONDS, com.agenttavern.game.card.Rank.FOUR),
                new Card(com.agenttavern.game.card.Suit.DIAMONDS, com.agenttavern.game.card.Rank.FIVE),
                new Card(com.agenttavern.game.card.Suit.DIAMONDS, com.agenttavern.game.card.Rank.SIX),
                new Card(com.agenttavern.game.card.Suit.DIAMONDS, com.agenttavern.game.card.Rank.SEVEN),
                new Card(com.agenttavern.game.card.Suit.DIAMONDS, com.agenttavern.game.card.Rank.EIGHT),
                new Card(com.agenttavern.game.card.Suit.DIAMONDS, com.agenttavern.game.card.Rank.NINE));
        return Hand.start(
                        hand(3),
                        List.of(first, second),
                        0,
                        new BlindLevel(1, 2),
                        com.agenttavern.game.card.Deck.ordered(deck))
                .hand();
    }

    private static PlayerId player(long id) {
        return new PlayerId(new UUID(0, id));
    }

    private static HandId hand(long id) {
        return new HandId(new UUID(1, id));
    }

    private static Card card(com.agenttavern.game.card.Rank rank, com.agenttavern.game.card.Suit suit) {
        return new Card(suit, rank);
    }
}
