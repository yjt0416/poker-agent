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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.Test;

class HandInvariantProperties {

    private static final int SCENARIO_COUNT = 500;
    private static final int MAX_ACTIONS = 500;
    private static final long DIRECTED_MUTATION_SEED = 17L;
    private static final RandomGeneratorFactory<RandomGenerator> RANDOM_FACTORY =
            RandomGeneratorFactory.of("L64X128MixRandom");

    @Test
    void chipsAreConservedAcrossEveryTransition() {
        check500("chipsAreConservedAcrossEveryTransition", 2026082808L, scenario -> {
            HandRun run = playToCompletion(scenario, TestPolicies::passiveLegalAction);
            long initial = run.transitions().getFirst().hand().totalChipsInSystem();

            for (HandTransition transition : run.transitions()) {
                assertThat(transition.hand().totalChipsInSystem())
                        .as("scenario seed=%s", scenario.seed())
                        .isEqualTo(initial);
            }
        });
    }

    @Test
    void everyObservableDealtCardIsPhysicallyUnique() {
        check500("everyObservableDealtCardIsPhysicallyUnique", 2026082809L, scenario -> {
            HandRun run = playToCompletion(scenario, TestPolicies::aggressiveLegalAction);
            List<Card> eventCards = dealtCardsFrom(run.events());
            Hand finalHand = run.transitions().getLast().hand();
            List<Card> exposedCards = exposedCardsFrom(finalHand);
            String description = "scenario seed=" + scenario.seed();

            assertCompleteRunoutExposesEveryPlayableCard(
                    scenario.players().size(), eventCards, exposedCards, description);
            assertThat(new HashSet<>(eventCards))
                    .as("event cards for %s", description)
                    .hasSize(eventCards.size());
            assertThat(new HashSet<>(exposedCards))
                    .as("board and hole cards for %s", description)
                    .hasSize(exposedCards.size());
            assertThat(exposedCards)
                    .as("observable dealt cards for %s", description)
                    .containsExactlyInAnyOrderElementsOf(eventCards);
        });
    }

    @Test
    void everyIncompleteHandHasAnActiveActorWithLegalActions() {
        check500("everyIncompleteHandHasAnActiveActorWithLegalActions", 2026082810L, scenario -> {
            HandRun run = playToCompletion(scenario, TestPolicies::passiveLegalAction);

            for (HandTransition transition : run.transitions()) {
                Hand hand = transition.hand();
                String description = "scenario seed=" + scenario.seed();
                if (hand.isComplete()) {
                    assertThat(hand.actor()).as(description).isNull();
                    assertThat(hand.legalActions().types()).as(description).isEmpty();
                    continue;
                }
                assertThat(hand.actor()).as(description).isNotNull();
                assertThat(hand.actor().status()).as(description).isEqualTo(ACTIVE);
                assertThat(hand.legalActions().types()).as(description).isNotEmpty();
            }
        });
    }

    @Test
    void everyTransitionExposesAnImmutableEventList() {
        check500("everyTransitionExposesAnImmutableEventList", 2026082811L, scenario -> {
            HandRun run = playToCompletion(scenario, TestPolicies::aggressiveLegalAction);

            for (HandTransition transition : run.transitions()) {
                assertThatThrownBy(() -> transition.events().add(null))
                        .as("transition events for scenario seed=%s", scenario.seed())
                        .isInstanceOf(UnsupportedOperationException.class);
            }
        });
    }

    @Test
    void rejectedActionCanBeReappliedWithoutChangingAnyPublicHandState() {
        check500("rejectedActionCanBeReappliedWithoutChangingAnyPublicHandState", 2026082812L,
                scenario -> {
                    Hand hand = scenario.startTransition().hand();
                    assertThat(hand.isComplete()).as("scenario seed=%s", scenario.seed()).isFalse();
                    SeatState actor = hand.actor();
                    PlayerId wrongPlayer = hand.seats().stream()
                            .map(SeatState::playerId)
                            .filter(playerId -> !playerId.equals(actor.playerId()))
                            .findFirst()
                            .orElseThrow();
                    PlayerAction action = TestPolicies.passiveLegalAction(hand.legalActions());

                    assertRejectedActionLeavesStateUnchanged(hand, wrongPlayer, action, scenario.seed());
                    assertRejectedActionLeavesStateUnchanged(hand, wrongPlayer, action, scenario.seed());
                });
    }

    @Test
    void passiveAndAggressiveLegalPoliciesAlwaysTerminateWithinTheHardActionLimit() {
        check500("passiveAndAggressiveLegalPoliciesAlwaysTerminateWithinTheHardActionLimit",
                2026082813L, scenario -> {
                    HandRun passive = playToCompletion(scenario, TestPolicies::passiveLegalAction);
                    HandRun aggressive = playToCompletion(scenario, TestPolicies::aggressiveLegalAction);

                    assertThat(passive.transitions().getLast().hand().isComplete())
                            .as("passive scenario seed=%s", scenario.seed())
                            .isTrue();
                    assertThat(aggressive.transitions().getLast().hand().isComplete())
                            .as("aggressive scenario seed=%s", scenario.seed())
                            .isTrue();
                    assertActionCountIsWithinHardLimit(
                            passive.actionCount(), "passive scenario seed=" + scenario.seed());
                    assertActionCountIsWithinHardLimit(
                            aggressive.actionCount(), "aggressive scenario seed=" + scenario.seed());
                });
    }

    @Test
    void fullRunoutPopulationRejectsEmptyObservableCardSources() {
        assertThatThrownBy(() -> assertCompleteRunoutExposesEveryPlayableCard(
                2, List.<Card>of(), List.<Card>of(), directedDescription()))
                .as(directedDescription())
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void hardActionLimitRejectsTheFiveHundredthAction() {
        assertThatThrownBy(() -> assertActionCountIsWithinHardLimit(MAX_ACTIONS, directedDescription()))
                .as(directedDescription())
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void publicStateComparisonRejectsChangedHoleCards() {
        PublicHandState original = PublicHandState.capture(
                scenarioFromSeed(DIRECTED_MUTATION_SEED).startTransition().hand());
        PlayerId firstPlayer = original.seats().getFirst().playerId();
        PlayerId secondPlayer = original.seats().get(1).playerId();
        Map<PlayerId, List<Card>> changedHoleCards = new LinkedHashMap<>(original.holeCards());
        changedHoleCards.put(firstPlayer, original.holeCards().get(secondPlayer));

        assertThatThrownBy(() -> assertPublicHandStateUnchanged(
                original, original.withHoleCards(changedHoleCards), directedDescription()))
                .as(directedDescription())
                .isInstanceOf(AssertionError.class);
    }

    private static void check500(
            String propertyName, long baseSeed, Consumer<HandScenario> propertyAssertion) {
        Set<Long> seenSeeds = new LinkedHashSet<>();
        ScenarioCoverage coverage = new ScenarioCoverage();
        String batchDescription = "%s reproducible base seed=%s, scenario count=%s"
                .formatted(propertyName, baseSeed, SCENARIO_COUNT);
        int executed = 0;

        for (int scenarioIndex = 0; scenarioIndex < SCENARIO_COUNT; scenarioIndex++) {
            long seed = Math.addExact(baseSeed, scenarioIndex);
            assertThat(seenSeeds.add(seed))
                    .as("%s must use a different scenario seed=%s", propertyName, seed)
                    .isTrue();
            try {
                HandScenario scenario = scenarioFromSeed(seed);
                coverage.record(scenario);
                propertyAssertion.accept(scenario);
                executed++;
            } catch (Throwable failure) {
                throw seededFailure(propertyName, seed, failure);
            }
        }

        assertThat(executed)
                .as("%s must execute exactly %s scenario assertions", batchDescription, SCENARIO_COUNT)
                .isEqualTo(SCENARIO_COUNT);
        assertThat(seenSeeds)
                .as("%s must retain exactly %s distinct scenario seeds", batchDescription, SCENARIO_COUNT)
                .hasSize(SCENARIO_COUNT)
                .contains(baseSeed, Math.addExact(baseSeed, SCENARIO_COUNT - 1L));
        coverage.assertRequiredVariations(propertyName, baseSeed);
    }

    private static AssertionError seededFailure(String propertyName, long seed, Throwable cause) {
        return new AssertionError(
                "property=" + propertyName + ", reproducible scenario seed=" + seed, cause);
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
            assertThat(hand.actor()).as("scenario seed=%s", scenario.seed()).isNotNull();
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
            int playerCount, List<Card> eventCards, List<Card> exposedCards, String description) {
        int expectedCards = Math.addExact(Math.multiplyExact(playerCount, 2), 5);
        assertThat(eventCards).as(description).isNotEmpty().hasSize(expectedCards);
        assertThat(exposedCards).as(description).isNotEmpty().hasSize(expectedCards);
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
            Hand hand, PlayerId playerId, PlayerAction action, long seed) {
        String description = "rejected action scenario seed=" + seed;
        PublicHandState before = PublicHandState.capture(hand);

        assertThatThrownBy(() -> hand.act(playerId, action))
                .as(description)
                .isInstanceOf(IllegalActionException.class);

        PublicHandState after = PublicHandState.capture(hand);
        assertPublicHandStateUnchanged(before, after, description);
    }

    private static void assertPublicHandStateUnchanged(
            PublicHandState before, PublicHandState after, String description) {
        assertThat(after.totalChips()).as(description).isEqualTo(before.totalChips());
        assertThat(after.board()).as(description).containsExactlyElementsOf(before.board());
        assertThat(after.seats()).as(description).containsExactlyElementsOf(before.seats());
        assertThat(after.holeCards()).as(description).isEqualTo(before.holeCards());
        assertThat(after.actor()).as(description).isEqualTo(before.actor());
        assertThat(after.legalActions()).as(description).isEqualTo(before.legalActions());
        assertThat(after.street()).as(description).isEqualTo(before.street());
        assertThat(after.complete()).as(description).isEqualTo(before.complete());
    }

    private static String directedDescription() {
        return "directed mutation scenario seed=" + DIRECTED_MUTATION_SEED;
    }

    private static HandScenario scenarioFromSeed(long seed) {
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

    private static void shuffle(List<Integer> values, RandomGenerator random) {
        for (int index = values.size() - 1; index > 0; index--) {
            Collections.swap(values, index, random.nextInt(index + 1));
        }
    }

    private record HandRun(List<HandTransition> transitions, List<HandEvent> events, int actionCount) {

        private HandRun {
            transitions = List.copyOf(transitions);
            events = List.copyOf(events);
        }
    }

    private static final class ScenarioCoverage {
        private final Set<Integer> playerCounts = new HashSet<>();
        private final Set<Integer> buttonSeats = new HashSet<>();
        private final Set<BlindLevel> blindLevels = new HashSet<>();
        private final Set<List<Card>> deckOrders = new HashSet<>();
        private boolean includesSparseSeats;
        private boolean includesShortStack;

        void record(HandScenario scenario) {
            assertThat(scenario.players().size())
                    .as("scenario seed=%s", scenario.seed())
                    .isBetween(2, 6);
            assertThat(scenario.players())
                    .as("scenario seed=%s", scenario.seed())
                    .extracting(PlayerStack::seatIndex)
                    .doesNotHaveDuplicates();
            assertThat(scenario.players())
                    .as("button must be occupied for scenario seed=%s", scenario.seed())
                    .extracting(PlayerStack::seatIndex)
                    .contains(scenario.buttonSeat());
            assertThat(scenario.blinds().smallBlind())
                    .as("scenario seed=%s", scenario.seed())
                    .isPositive();
            assertThat(scenario.blinds().bigBlind())
                    .as("scenario seed=%s", scenario.seed())
                    .isGreaterThanOrEqualTo(Math.multiplyExact(scenario.blinds().smallBlind(), 2));
            assertThat(scenario.deck().remaining())
                    .as("scenario seed=%s", scenario.seed())
                    .isEqualTo(52);

            playerCounts.add(scenario.players().size());
            buttonSeats.add(scenario.buttonSeat());
            blindLevels.add(scenario.blinds());
            deckOrders.add(deckOrder(scenario.deck()));
            includesSparseSeats |= scenario.players().size() < 6;
            includesShortStack |= scenario.players().stream()
                    .mapToLong(PlayerStack::chips)
                    .min()
                    .orElseThrow() < scenario.players().stream()
                    .mapToLong(PlayerStack::chips)
                    .max()
                    .orElseThrow();
        }

        void assertRequiredVariations(String propertyName, long baseSeed) {
            String description = "%s reproducible base seed=%s, scenario count=%s"
                    .formatted(propertyName, baseSeed, SCENARIO_COUNT);
            assertThat(playerCounts).as(description).containsExactlyInAnyOrder(2, 3, 4, 5, 6);
            assertThat(includesSparseSeats).as(description).isTrue();
            assertThat(includesShortStack).as(description).isTrue();
            assertThat(buttonSeats).as(description).hasSizeGreaterThan(1);
            assertThat(blindLevels).as(description).hasSizeGreaterThan(1);
            assertThat(deckOrders).as(description).hasSizeGreaterThan(1);
        }

        private static List<Card> deckOrder(Deck deck) {
            List<Card> cards = new ArrayList<>(deck.remaining());
            Deck remaining = deck;
            while (remaining.remaining() > 0) {
                Deck.Draw draw = remaining.draw();
                cards.add(draw.card());
                remaining = draw.deck();
            }
            return List.copyOf(cards);
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
                    totalChips, board, seats, changedHoleCards, actor, legalActions, street, complete);
        }

        private static Map<PlayerId, List<Card>> deeplyImmutableHoleCards(
                Map<PlayerId, List<Card>> source) {
            Map<PlayerId, List<Card>> copy = new LinkedHashMap<>();
            source.forEach((playerId, cards) -> copy.put(playerId, List.copyOf(cards)));
            return Collections.unmodifiableMap(copy);
        }
    }

    private record HandScenario(
            long seed,
            HandId handId,
            List<PlayerStack> players,
            int buttonSeat,
            BlindLevel blinds,
            Deck deck) {

        private HandScenario {
            players = List.copyOf(players);
        }

        HandTransition startTransition() {
            return Hand.start(handId, players, buttonSeat, blinds, deck);
        }
    }
}
