package com.agenttavern.game;

import static com.agenttavern.game.betting.PlayerStatus.ACTIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenttavern.game.betting.IllegalActionException;
import com.agenttavern.game.betting.LegalActions;
import com.agenttavern.game.betting.PlayerAction;
import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.betting.SeatState;
import com.agenttavern.game.betting.Street;
import com.agenttavern.game.card.Card;
import com.agenttavern.game.card.Deck;
import com.agenttavern.game.hand.BlindLevel;
import com.agenttavern.game.hand.Hand;
import com.agenttavern.game.hand.HandEvent;
import com.agenttavern.game.hand.HandId;
import com.agenttavern.game.hand.HandTransition;
import com.agenttavern.game.hand.PlayerStack;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class HandInvariantProperties {

    private static final int MAX_ACTIONS = 500;
    private static final RandomGeneratorFactory<RandomGenerator> RANDOM_FACTORY =
            RandomGeneratorFactory.of("L64X128MixRandom");

    @Property(tries = 500, seed = "2026082808")
    void chipsAreConservedAcrossEveryTransition(@ForAll("handScenarios") HandScenario scenario) {
        HandRun run = playToCompletion(scenario, TestPolicies::passiveLegalAction);
        long initial = run.transitions().getFirst().hand().totalChipsInSystem();

        for (HandTransition transition : run.transitions()) {
            assertThat(transition.hand().totalChipsInSystem())
                    .as("scenario seed=%s", scenario.seed())
                    .isEqualTo(initial);
        }
    }

    @Property(tries = 500, seed = "2026082809")
    void everyObservableDealtCardIsPhysicallyUnique(@ForAll("handScenarios") HandScenario scenario) {
        HandRun run = playToCompletion(scenario, TestPolicies::aggressiveLegalAction);
        List<Card> eventCards = dealtCardsFrom(run.events());
        Hand finalHand = run.transitions().getLast().hand();
        List<Card> exposedCards = exposedCardsFrom(finalHand);

        assertCompleteRunoutExposesEveryPlayableCard(
                scenario.players().size(), eventCards, exposedCards);
        assertThat(new HashSet<>(eventCards))
                .as("event cards for scenario seed=%s", scenario.seed())
                .hasSize(eventCards.size());
        assertThat(new HashSet<>(exposedCards))
                .as("board and hole cards for scenario seed=%s", scenario.seed())
                .hasSize(exposedCards.size());
        assertThat(exposedCards).containsExactlyInAnyOrderElementsOf(eventCards);
    }

    @Example
    void fullRunoutPopulationRejectsEmptyObservableCardSources() {
        assertThatThrownBy(() -> assertCompleteRunoutExposesEveryPlayableCard(
                2, List.<Card>of(), List.<Card>of())).isInstanceOf(AssertionError.class);
    }

    @Example
    void hardActionLimitRejectsTheFiveHundredthAction() {
        assertThatThrownBy(() -> assertActionCountIsWithinHardLimit(MAX_ACTIONS, "example"))
                .isInstanceOf(AssertionError.class);
    }

    @Example
    void publicStateComparisonRejectsChangedHoleCards() {
        PublicHandState original = PublicHandState.capture(
                HandScenario.fromSeed(17).startTransition().hand());
        PlayerId firstPlayer = original.seats().getFirst().playerId();
        PlayerId secondPlayer = original.seats().get(1).playerId();
        Map<PlayerId, List<Card>> changedHoleCards = new LinkedHashMap<>(original.holeCards());
        changedHoleCards.put(firstPlayer, original.holeCards().get(secondPlayer));

        assertThatThrownBy(() -> assertPublicHandStateUnchanged(
                original, original.withHoleCards(changedHoleCards))).isInstanceOf(AssertionError.class);
    }

    @Property(tries = 500, seed = "2026082810")
    void everyIncompleteHandHasAnActiveActorWithLegalActions(
            @ForAll("handScenarios") HandScenario scenario) {
        HandRun run = playToCompletion(scenario, TestPolicies::passiveLegalAction);

        for (HandTransition transition : run.transitions()) {
            Hand hand = transition.hand();
            if (hand.isComplete()) {
                assertThat(hand.actor()).isNull();
                assertThat(hand.legalActions().types()).isEmpty();
                continue;
            }
            assertThat(hand.actor())
                    .as("scenario seed=%s", scenario.seed())
                    .isNotNull();
            assertThat(hand.actor().status()).isEqualTo(ACTIVE);
            assertThat(hand.legalActions().types()).isNotEmpty();
        }
    }

    @Property(tries = 500, seed = "2026082811")
    void everyTransitionExposesAnImmutableEventList(@ForAll("handScenarios") HandScenario scenario) {
        HandRun run = playToCompletion(scenario, TestPolicies::aggressiveLegalAction);

        for (HandTransition transition : run.transitions()) {
            assertThatThrownBy(() -> transition.events().add(null))
                    .as("transition events for scenario seed=%s", scenario.seed())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Property(tries = 500, seed = "2026082812")
    void rejectedActionCanBeReappliedWithoutChangingAnyPublicHandState(
            @ForAll("handScenarios") HandScenario scenario) {
        Hand hand = scenario.startTransition().hand();
        assertThat(hand.isComplete()).isFalse();
        SeatState actor = hand.actor();
        PlayerId wrongPlayer = hand.seats().stream()
                .map(SeatState::playerId)
                .filter(playerId -> !playerId.equals(actor.playerId()))
                .findFirst()
                .orElseThrow();
        PlayerAction action = TestPolicies.passiveLegalAction(hand.legalActions());

        assertRejectedActionLeavesStateUnchanged(hand, wrongPlayer, action);
        assertRejectedActionLeavesStateUnchanged(hand, wrongPlayer, action);
    }

    @Property(tries = 500, seed = "2026082813")
    void passiveAndAggressiveLegalPoliciesAlwaysTerminateWithinTheHardActionLimit(
            @ForAll("handScenarios") HandScenario scenario) {
        HandRun passive = playToCompletion(scenario, TestPolicies::passiveLegalAction);
        HandRun aggressive = playToCompletion(scenario, TestPolicies::aggressiveLegalAction);

        assertThat(passive.transitions().getLast().hand().isComplete()).isTrue();
        assertThat(aggressive.transitions().getLast().hand().isComplete()).isTrue();
        assertActionCountIsWithinHardLimit(
                passive.actionCount(), "passive scenario seed=" + scenario.seed());
        assertActionCountIsWithinHardLimit(
                aggressive.actionCount(), "aggressive scenario seed=" + scenario.seed());
    }

    @Provide
    Arbitrary<HandScenario> handScenarios() {
        return Arbitraries.longs()
                .between(-Long.MAX_VALUE, Long.MAX_VALUE)
                .map(HandScenario::fromSeed);
    }

    private static HandRun playToCompletion(
            HandScenario scenario, Function<LegalActions, PlayerAction> policy) {
        HandTransition transition = scenario.startTransition();
        List<HandTransition> transitions = new ArrayList<>();
        List<HandEvent> events = new ArrayList<>();
        transitions.add(transition);
        events.addAll(transition.events());
        Hand hand = transition.hand();
        int actions = 0;

        while (!hand.isComplete()) {
            assertActionCountIsWithinHardLimit(
                    actions + 1,
                    "next action for scenario seed=%s, street=%s, actor=%s, legal=%s, seats=%s"
                            .formatted(
                                    scenario.seed(),
                                    hand.street(),
                                    hand.actor(),
                                    hand.legalActions(),
                                    hand.seats()));
            assertThat(hand.actor()).isNotNull();
            PlayerAction action = policy.apply(hand.legalActions());
            transition = hand.act(hand.actor().playerId(), action);
            hand = transition.hand();
            transitions.add(transition);
            events.addAll(transition.events());
            actions++;
        }
        return new HandRun(transitions, events, actions);
    }

    private static List<Card> dealtCardsFrom(List<HandEvent> events) {
        List<Card> cards = new ArrayList<>();
        for (HandEvent event : events) {
            if (event instanceof HandEvent.HoleCardsDealt holeCardsDealt) {
                holeCardsDealt.holeCards().values().forEach(cards::addAll);
            } else if (event instanceof HandEvent.CommunityCardsDealt communityCardsDealt) {
                cards.addAll(communityCardsDealt.cards());
            }
        }
        return cards;
    }

    private static void assertCompleteRunoutExposesEveryPlayableCard(
            int playerCount, List<Card> eventCards, List<Card> exposedCards) {
        int expectedCards = Math.addExact(Math.multiplyExact(playerCount, 2), 5);
        assertThat(eventCards).isNotEmpty().hasSize(expectedCards);
        assertThat(exposedCards).isNotEmpty().hasSize(expectedCards);
    }

    private static void assertActionCountIsWithinHardLimit(int actionCount, String description) {
        assertThat(actionCount).as(description).isLessThan(MAX_ACTIONS);
    }

    private static List<Card> exposedCardsFrom(Hand hand) {
        List<Card> cards = new ArrayList<>(hand.board());
        hand.seats().forEach(seat -> cards.addAll(hand.holeCards(seat.playerId())));
        return cards;
    }

    private static void assertRejectedActionLeavesStateUnchanged(
            Hand hand, PlayerId playerId, PlayerAction action) {
        PublicHandState before = PublicHandState.capture(hand);

        assertThatThrownBy(() -> hand.act(playerId, action))
                .isInstanceOf(IllegalActionException.class);

        PublicHandState after = PublicHandState.capture(hand);
        assertPublicHandStateUnchanged(before, after);
    }

    private static void assertPublicHandStateUnchanged(
            PublicHandState before, PublicHandState after) {
        assertThat(after.totalChips()).isEqualTo(before.totalChips());
        assertThat(after.board()).containsExactlyElementsOf(before.board());
        assertThat(after.seats()).containsExactlyElementsOf(before.seats());
        assertThat(after.holeCards()).isEqualTo(before.holeCards());
        assertThat(after.actor()).isEqualTo(before.actor());
        assertThat(after.legalActions()).isEqualTo(before.legalActions());
        assertThat(after.street()).isEqualTo(before.street());
        assertThat(after.complete()).isEqualTo(before.complete());
    }

    private record HandRun(List<HandTransition> transitions, List<HandEvent> events, int actionCount) {

        private HandRun {
            transitions = List.copyOf(transitions);
            events = List.copyOf(events);
        }
    }

    private record PublicHandState(
            long totalChips,
            List<Card> board,
            List<SeatState> seats,
            Map<PlayerId, List<Card>> holeCards,
            SeatState actor,
            LegalActions legalActions,
            Street street,
            boolean complete) {

        private PublicHandState {
            board = List.copyOf(board);
            seats = List.copyOf(seats);
            holeCards = deeplyImmutableHoleCards(holeCards);
        }

        static PublicHandState capture(Hand hand) {
            Map<PlayerId, List<Card>> holeCards = new LinkedHashMap<>();
            hand.seats().forEach(seat -> holeCards.put(
                    seat.playerId(), List.copyOf(hand.holeCards(seat.playerId()))));
            return new PublicHandState(
                    hand.totalChipsInSystem(),
                    hand.board(),
                    hand.seats(),
                    holeCards,
                    hand.actor(),
                    hand.legalActions(),
                    hand.street(),
                    hand.isComplete());
        }

        PublicHandState withHoleCards(Map<PlayerId, List<Card>> changedHoleCards) {
            return new PublicHandState(
                    totalChips,
                    board,
                    seats,
                    changedHoleCards,
                    actor,
                    legalActions,
                    street,
                    complete);
        }

        private static Map<PlayerId, List<Card>> deeplyImmutableHoleCards(
                Map<PlayerId, List<Card>> source) {
            Map<PlayerId, List<Card>> copy = new LinkedHashMap<>();
            source.forEach((playerId, cards) -> copy.put(playerId, List.copyOf(cards)));
            return Collections.unmodifiableMap(copy);
        }
    }

    record HandScenario(
            long seed,
            HandId handId,
            List<PlayerStack> players,
            int buttonSeat,
            BlindLevel blinds,
            Deck deck) {

        HandScenario {
            players = List.copyOf(players);
        }

        static HandScenario fromSeed(long seed) {
            RandomGenerator random = RANDOM_FACTORY.create(seed);
            int playerCount = random.nextInt(2, 7);
            List<Integer> seats = new ArrayList<>(List.of(0, 1, 2, 3, 4, 5));
            shuffle(seats, random);
            List<Integer> occupiedSeats = new ArrayList<>(seats.subList(0, playerCount));
            int buttonSeat = occupiedSeats.get(random.nextInt(occupiedSeats.size()));
            int shortStackSeat = occupiedSeats.get(random.nextInt(occupiedSeats.size()));

            List<Long> stacks = new ArrayList<>(playerCount);
            for (int ignored = 0; ignored < playerCount; ignored++) {
                stacks.add(random.nextLong(100, 20_001));
            }
            stacks.set(occupiedSeats.indexOf(shortStackSeat), 100L);
            long largestStack = stacks.stream().mapToLong(Long::longValue).max().orElseThrow();
            long minSmallBlind = Math.max(1, Math.ceilDiv(largestStack, 200));
            long maxSmallBlind = Math.min(500, largestStack / 2);
            long smallBlind = random.nextLong(minSmallBlind, maxSmallBlind + 1);
            BlindLevel blinds = new BlindLevel(smallBlind, Math.multiplyExact(smallBlind, 2));

            if (playerCount == 2) {
                int buttonIndex = occupiedSeats.indexOf(buttonSeat);
                stacks.set(buttonIndex, Math.max(stacks.get(buttonIndex), smallBlind + 1));
                int bigBlindIndex = buttonIndex == 0 ? 1 : 0;
                stacks.set(bigBlindIndex, Math.max(stacks.get(bigBlindIndex), smallBlind + 1));
            }

            List<PlayerStack> players = new ArrayList<>(playerCount);
            for (int index = 0; index < playerCount; index++) {
                int seatIndex = occupiedSeats.get(index);
                players.add(new PlayerStack(
                        new PlayerId(new UUID(seed, seatIndex + 1L)), seatIndex, stacks.get(index)));
            }
            return new HandScenario(
                    seed,
                    new HandId(new UUID(seed, Long.rotateLeft(seed, 17))),
                    players,
                    buttonSeat,
                    blinds,
                    Deck.standard().shuffled(random));
        }

        HandTransition startTransition() {
            return Hand.start(handId, players, buttonSeat, blinds, deck);
        }

        private static void shuffle(List<Integer> values, RandomGenerator random) {
            for (int index = values.size() - 1; index > 0; index--) {
                Collections.swap(values, index, random.nextInt(index + 1));
            }
        }
    }
}
