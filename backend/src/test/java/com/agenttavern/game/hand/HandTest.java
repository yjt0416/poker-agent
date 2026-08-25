package com.agenttavern.game.hand;

import static com.agenttavern.game.betting.PlayerStatus.ACTIVE;
import static com.agenttavern.game.betting.PlayerStatus.ALL_IN;
import static com.agenttavern.game.betting.Street.PREFLOP;
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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.betting.PlayerAction;
import com.agenttavern.game.betting.SeatState;
import com.agenttavern.game.betting.IllegalActionException;
import com.agenttavern.game.card.Card;
import com.agenttavern.game.card.Deck;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HandTest {

    private static final BlindLevel BLINDS = new BlindLevel(50, 100);

    @Test
    void fixedDeckStartDealsClockwisePostsShortBlindAndEmitsDeterministicEvents() {
        HandId handId = hand(1);
        PlayerStack button = player(1, 5, 1_000);
        PlayerStack smallBlind = player(2, 0, 40);
        PlayerStack bigBlind = player(3, 2, 1_000);
        List<Card> cards = fixedCardsForThreePlayers();

        HandTransition transition = Hand.start(
                handId,
                List.of(button, bigBlind, smallBlind),
                5,
                BLINDS,
                Deck.ordered(cards));

        Hand started = transition.hand();
        assertThat(started.street()).isEqualTo(PREFLOP);
        assertThat(started.board()).isEmpty();
        assertThat(started.actor().playerId()).isEqualTo(button.playerId());
        assertThat(started.holeCards(smallBlind.playerId()))
                .containsExactly(cards.get(0), cards.get(3));
        assertThat(started.holeCards(bigBlind.playerId()))
                .containsExactly(cards.get(1), cards.get(4));
        assertThat(started.holeCards(button.playerId()))
                .containsExactly(cards.get(2), cards.get(5));
        assertThat(started.seats())
                .extracting(SeatState::seatIndex)
                .containsExactly(0, 2, 5);
        assertThat(started.seats())
                .extracting(SeatState::streetCommitted)
                .containsExactly(40L, 100L, 0L);
        assertThat(started.seats())
                .extracting(SeatState::status)
                .containsExactly(ALL_IN, ACTIVE, ACTIVE);

        assertThat(transition.events())
                .extracting(Object::getClass)
                .containsExactly(
                        HandEvent.HandStarted.class,
                        HandEvent.HoleCardsDealt.class,
                        HandEvent.BlindsPosted.class);
        HandEvent.HoleCardsDealt dealt =
                (HandEvent.HoleCardsDealt) transition.events().get(1);
        assertThat(dealt.holeCards().keySet())
                .containsExactly(
                        smallBlind.playerId(), bigBlind.playerId(), button.playerId());
        assertThat(dealt.holeCards().get(smallBlind.playerId()))
                .containsExactly(cards.get(0), cards.get(3));
        HandEvent.BlindsPosted posted =
                (HandEvent.BlindsPosted) transition.events().get(2);
        assertThat(posted.smallBlindPlayerId()).isEqualTo(smallBlind.playerId());
        assertThat(posted.smallBlindAmount()).isEqualTo(40);
        assertThat(posted.bigBlindPlayerId()).isEqualTo(bigBlind.playerId());
        assertThat(posted.bigBlindAmount()).isEqualTo(100);
    }

    @Test
    void headsUpButtonPostsSmallBlindActsFirstAndReceivesSecondCardEachRound() {
        PlayerStack button = player(11, 4, 1_000);
        PlayerStack bigBlind = player(12, 1, 1_000);
        List<Card> cards = fixedCardsForHeadsUp();

        HandTransition transition = Hand.start(
                hand(2),
                List.of(bigBlind, button),
                4,
                BLINDS,
                Deck.ordered(cards));

        Hand started = transition.hand();
        assertThat(started.actor().playerId()).isEqualTo(button.playerId());
        assertThat(started.holeCards(bigBlind.playerId()))
                .containsExactly(cards.get(0), cards.get(2));
        assertThat(started.holeCards(button.playerId()))
                .containsExactly(cards.get(1), cards.get(3));
        assertThat(seat(started, button.playerId()).streetCommitted()).isEqualTo(50);
        assertThat(seat(started, bigBlind.playerId()).streetCommitted()).isEqualTo(100);

        HandEvent.BlindsPosted posted =
                (HandEvent.BlindsPosted) transition.events().get(2);
        assertThat(posted.smallBlindPlayerId()).isEqualTo(button.playerId());
        assertThat(posted.bigBlindPlayerId()).isEqualTo(bigBlind.playerId());
    }

    @Test
    void blindsThatPutEveryPlayerAllInRunTheBoardAndCompleteWithoutAnActor() {
        PlayerStack button = player(21, 4, 50);
        PlayerStack bigBlind = player(22, 1, 100);

        HandTransition transition = Hand.start(
                hand(3),
                List.of(button, bigBlind),
                4,
                BLINDS,
                Deck.ordered(fixedCardsForHeadsUp()));

        Hand completed = transition.hand();
        assertThat(completed.isComplete()).isTrue();
        assertThat(completed.actor()).isNull();
        assertThat(completed.legalActions().types()).isEmpty();
        assertThat(completed.board()).containsExactly(
                card(THREE, CLUBS),
                card(FOUR, CLUBS),
                card(FIVE, CLUBS),
                card(SEVEN, DIAMONDS),
                card(NINE, HEARTS));
        assertThat(seat(completed, button.playerId()).stack()).isZero();
        assertThat(seat(completed, bigBlind.playerId()).stack()).isEqualTo(150);
        assertThat(completed.seats())
                .extracting(SeatState::handCommitted)
                .containsOnly(0L);
        assertThat(completed.totalChipsInSystem()).isEqualTo(150);
        assertThat(transition.events())
                .filteredOn(HandEvent.CommunityCardsDealt.class::isInstance)
                .map(HandEvent.CommunityCardsDealt.class::cast)
                .extracting(event -> event.cards().size())
                .containsExactly(3, 1, 1);
        assertThat(transition.events())
                .extracting(Object::getClass)
                .endsWith(HandEvent.PotsAwarded.class, HandEvent.HandCompleted.class);
        assertThat(transition.events())
                .filteredOn(HandEvent.HandStarted.class::isInstance)
                .hasSize(1);
        assertThat(transition.events())
                .filteredOn(HandEvent.HandCompleted.class::isInstance)
                .hasSize(1);
        assertThatThrownBy(() -> completed.act(button.playerId(), com.agenttavern.game.betting.PlayerAction.check()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void completingPreflopResetsCommitmentsDealsFlopAndStartsLeftOfButton() {
        PlayerStack button = player(31, 5, 1_000);
        PlayerStack smallBlind = player(32, 0, 1_000);
        PlayerStack bigBlind = player(33, 2, 1_000);
        Hand hand = Hand.start(
                        hand(4),
                        List.of(button, smallBlind, bigBlind),
                        5,
                        BLINDS,
                        Deck.ordered(fixedCardsForThreePlayers()))
                .hand();

        HandTransition buttonCall = hand.act(button.playerId(), PlayerAction.call());
        hand = buttonCall.hand();
        assertThat(buttonCall.events())
                .singleElement()
                .isInstanceOf(HandEvent.PlayerActed.class);
        assertThat(hand.actor().playerId()).isEqualTo(smallBlind.playerId());

        hand = hand.act(smallBlind.playerId(), PlayerAction.call()).hand();
        HandTransition bigBlindCheck = hand.act(bigBlind.playerId(), PlayerAction.check());

        Hand flop = bigBlindCheck.hand();
        assertThat(flop.street()).isEqualTo(com.agenttavern.game.betting.Street.FLOP);
        assertThat(flop.board()).containsExactly(
                card(THREE, CLUBS), card(FOUR, CLUBS), card(FIVE, CLUBS));
        assertThat(flop.seats())
                .extracting(SeatState::streetCommitted)
                .containsOnly(0L);
        assertThat(flop.seats())
                .extracting(SeatState::handCommitted)
                .containsOnly(100L);
        assertThat(flop.actor().playerId()).isEqualTo(smallBlind.playerId());
        assertThat(flop.legalActions().callAmount()).isZero();
        assertThat(flop.legalActions().minRaiseTo()).hasValue(100);
        assertThat(bigBlindCheck.events())
                .extracting(Object::getClass)
                .containsExactly(
                        HandEvent.PlayerActed.class,
                        HandEvent.CommunityCardsDealt.class);
    }

    @Test
    void foldingToTheBigBlindAwardsEveryContributionWithoutDealingTheBoard() {
        PlayerStack button = player(41, 5, 1_000);
        PlayerStack smallBlind = player(42, 0, 1_000);
        PlayerStack bigBlind = player(43, 2, 1_000);
        HandTransition start = Hand.start(
                hand(5),
                List.of(button, smallBlind, bigBlind),
                5,
                BLINDS,
                Deck.ordered(fixedCardsForThreePlayers()));
        Hand hand = start.hand();
        List<HandEvent> actionEvents = new java.util.ArrayList<>(start.events());

        HandTransition buttonFold = hand.act(button.playerId(), PlayerAction.fold());
        actionEvents.addAll(buttonFold.events());
        HandTransition smallBlindFold =
                buttonFold.hand().act(smallBlind.playerId(), PlayerAction.fold());
        actionEvents.addAll(smallBlindFold.events());

        Hand completed = smallBlindFold.hand();
        assertThat(completed.isComplete()).isTrue();
        assertThat(completed.board()).isEmpty();
        assertThat(completed.actor()).isNull();
        assertThat(completed.legalActions().types()).isEmpty();
        assertThat(seat(completed, bigBlind.playerId()).stack()).isEqualTo(1_050);
        assertThat(completed.seats())
                .extracting(SeatState::handCommitted)
                .containsOnly(0L);
        assertThat(completed.totalChipsInSystem()).isEqualTo(3_000);
        assertThat(actionEvents)
                .filteredOn(HandEvent.PlayerActed.class::isInstance)
                .hasSize(2);
        assertThat(actionEvents)
                .filteredOn(HandEvent.HandStarted.class::isInstance)
                .hasSize(1);
        assertThat(actionEvents)
                .filteredOn(HandEvent.HandCompleted.class::isInstance)
                .hasSize(1);
        assertThat(actionEvents)
                .noneMatch(HandEvent.CommunityCardsDealt.class::isInstance);
        assertThat(smallBlindFold.events())
                .extracting(Object::getClass)
                .containsExactly(
                        HandEvent.PlayerActed.class,
                        HandEvent.PotsAwarded.class,
                        HandEvent.HandCompleted.class);
        HandEvent.PotsAwarded awarded = (HandEvent.PotsAwarded) smallBlindFold.events().get(1);
        assertThat(awarded.payouts()).containsExactly(java.util.Map.entry(bigBlind.playerId(), 150L));
        assertThat(awarded.pots()).extracting(com.agenttavern.game.showdown.Pot::amount)
                .containsExactly(100L, 50L);
    }

    @Test
    void checkingAndCallingThroughRiverAwardsTheKnownBestHand() {
        PlayerStack button = player(51, 5, 1_000);
        PlayerStack smallBlind = player(52, 0, 1_000);
        PlayerStack bigBlind = player(53, 2, 1_000);
        HandTransition start = Hand.start(
                hand(6),
                List.of(button, smallBlind, bigBlind),
                5,
                BLINDS,
                Deck.ordered(fixedCardsForThreePlayers()));
        List<HandEvent> events = new java.util.ArrayList<>(start.events());
        Hand hand = start.hand();
        int actions = 0;

        while (!hand.isComplete()) {
            PlayerAction action = hand.legalActions().types()
                            .contains(com.agenttavern.game.betting.ActionType.CALL)
                    ? PlayerAction.call()
                    : PlayerAction.check();
            HandTransition transition = hand.act(hand.actor().playerId(), action);
            events.addAll(transition.events());
            hand = transition.hand();
            actions++;
        }

        assertThat(actions).isEqualTo(12);
        assertThat(hand.street()).isEqualTo(com.agenttavern.game.betting.Street.SHOWDOWN);
        assertThat(hand.board()).containsExactly(
                card(THREE, CLUBS),
                card(FOUR, CLUBS),
                card(FIVE, CLUBS),
                card(SEVEN, DIAMONDS),
                card(NINE, HEARTS));
        assertThat(seat(hand, smallBlind.playerId()).stack()).isEqualTo(1_200);
        assertThat(seat(hand, bigBlind.playerId()).stack()).isEqualTo(900);
        assertThat(seat(hand, button.playerId()).stack()).isEqualTo(900);
        assertThat(hand.totalChipsInSystem()).isEqualTo(3_000);
        assertThat(events).filteredOn(HandEvent.HandStarted.class::isInstance).hasSize(1);
        assertThat(events).filteredOn(HandEvent.HandCompleted.class::isInstance).hasSize(1);
        assertThat(events).filteredOn(HandEvent.PlayerActed.class::isInstance).hasSize(12);
        assertThat(events)
                .filteredOn(HandEvent.CommunityCardsDealt.class::isInstance)
                .map(HandEvent.CommunityCardsDealt.class::cast)
                .extracting(event -> event.cards().size())
                .containsExactly(3, 1, 1);
        HandEvent.PotsAwarded awarded = events.stream()
                .filter(HandEvent.PotsAwarded.class::isInstance)
                .map(HandEvent.PotsAwarded.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(awarded.payouts())
                .containsExactly(java.util.Map.entry(smallBlind.playerId(), 300L));
        assertThat(awarded.pots()).extracting(com.agenttavern.game.showdown.Pot::amount)
                .containsExactly(300L);
    }

    @Test
    void threeDifferentAllInsRunOutAndAwardMainAndSidePotsIndependently() {
        PlayerStack button = player(61, 5, 300);
        PlayerStack smallBlind = player(62, 0, 100);
        PlayerStack bigBlind = player(63, 2, 200);
        HandTransition start = Hand.start(
                hand(7),
                List.of(button, smallBlind, bigBlind),
                5,
                BLINDS,
                Deck.ordered(fixedCardsForThreePlayers()));
        List<HandEvent> events = new java.util.ArrayList<>(start.events());
        Hand hand = start.hand();

        HandTransition buttonAllIn =
                hand.act(button.playerId(), PlayerAction.allIn(300));
        events.addAll(buttonAllIn.events());
        HandTransition smallBlindAllIn = buttonAllIn.hand()
                .act(smallBlind.playerId(), PlayerAction.allIn(100));
        events.addAll(smallBlindAllIn.events());
        HandTransition bigBlindAllIn = smallBlindAllIn.hand()
                .act(bigBlind.playerId(), PlayerAction.allIn(200));
        events.addAll(bigBlindAllIn.events());

        Hand completed = bigBlindAllIn.hand();
        assertThat(completed.isComplete()).isTrue();
        assertThat(completed.board()).hasSize(5);
        assertThat(seat(completed, smallBlind.playerId()).stack()).isEqualTo(300);
        assertThat(seat(completed, bigBlind.playerId()).stack()).isEqualTo(200);
        assertThat(seat(completed, button.playerId()).stack()).isEqualTo(100);
        assertThat(completed.totalChipsInSystem()).isEqualTo(600);
        assertThat(events).filteredOn(HandEvent.PlayerActed.class::isInstance).hasSize(3);
        assertThat(events).filteredOn(HandEvent.HandStarted.class::isInstance).hasSize(1);
        assertThat(events).filteredOn(HandEvent.HandCompleted.class::isInstance).hasSize(1);
        assertThat(events)
                .filteredOn(HandEvent.CommunityCardsDealt.class::isInstance)
                .map(HandEvent.CommunityCardsDealt.class::cast)
                .extracting(event -> event.cards().size())
                .containsExactly(3, 1, 1);
        HandEvent.PotsAwarded awarded = events.stream()
                .filter(HandEvent.PotsAwarded.class::isInstance)
                .map(HandEvent.PotsAwarded.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(awarded.pots()).extracting(com.agenttavern.game.showdown.Pot::amount)
                .containsExactly(300L, 200L, 100L);
        assertThat(awarded.payouts()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                smallBlind.playerId(), 300L,
                bigBlind.playerId(), 200L,
                button.playerId(), 100L));
        assertThat(awarded.payouts().keySet()).containsExactly(
                smallBlind.playerId(), bigBlind.playerId(), button.playerId());
    }

    @Test
    void headsUpButtonActsPreflopAndBigBlindActsFirstPostflop() {
        PlayerStack button = player(71, 4, 1_000);
        PlayerStack bigBlind = player(72, 1, 1_000);
        Hand hand = Hand.start(
                        hand(8),
                        List.of(button, bigBlind),
                        4,
                        BLINDS,
                        Deck.ordered(fixedCardsForHeadsUp()))
                .hand();

        assertThat(hand.actor().playerId()).isEqualTo(button.playerId());
        hand = hand.act(button.playerId(), PlayerAction.call()).hand();
        assertThat(hand.actor().playerId()).isEqualTo(bigBlind.playerId());
        hand = hand.act(bigBlind.playerId(), PlayerAction.check()).hand();

        assertThat(hand.street()).isEqualTo(com.agenttavern.game.betting.Street.FLOP);
        assertThat(hand.actor().playerId()).isEqualTo(bigBlind.playerId());
        hand = hand.act(bigBlind.playerId(), PlayerAction.check()).hand();
        assertThat(hand.actor().playerId()).isEqualTo(button.playerId());
    }

    @Test
    void rejectedActionLeavesOriginalHandUnchangedAndEmitsNoSuccessfulTransition() {
        PlayerStack button = player(81, 5, 1_000);
        PlayerStack smallBlind = player(82, 0, 1_000);
        PlayerStack bigBlind = player(83, 2, 1_000);
        Hand original = Hand.start(
                        hand(9),
                        List.of(button, smallBlind, bigBlind),
                        5,
                        BLINDS,
                        Deck.ordered(fixedCardsForThreePlayers()))
                .hand();
        List<SeatState> originalSeats = original.seats();
        List<Card> originalBoard = original.board();
        SeatState originalActor = original.actor();

        assertThatThrownBy(() -> original.act(smallBlind.playerId(), PlayerAction.call()))
                .isInstanceOf(IllegalActionException.class);
        assertThatThrownBy(() -> original.act(button.playerId(), PlayerAction.check()))
                .isInstanceOf(IllegalActionException.class);

        assertThat(original.seats()).isSameAs(originalSeats);
        assertThat(original.board()).isSameAs(originalBoard);
        assertThat(original.actor()).isSameAs(originalActor);
        assertThat(original.street()).isEqualTo(PREFLOP);
        assertThat(original.totalChipsInSystem()).isEqualTo(3_000);
        HandTransition accepted = original.act(button.playerId(), PlayerAction.call());
        assertThat(accepted.events())
                .singleElement()
                .isInstanceOf(HandEvent.PlayerActed.class);
        assertThat(accepted.hand()).isNotSameAs(original);
        assertThat(original.actor()).isSameAs(originalActor);
    }

    @Test
    void shortBlindCommitmentsSetTheActualCurrentBetButKeepTheBlindRaiseSize() {
        PlayerStack button = player(91, 5, 1_000);
        PlayerStack smallBlind = player(92, 0, 20);
        PlayerStack bigBlind = player(93, 2, 40);

        Hand hand = Hand.start(
                        hand(10),
                        List.of(button, smallBlind, bigBlind),
                        5,
                        BLINDS,
                        Deck.ordered(fixedCardsForThreePlayers()))
                .hand();

        assertThat(hand.actor().playerId()).isEqualTo(button.playerId());
        assertThat(hand.legalActions().callAmount()).isEqualTo(40);
        assertThat(hand.legalActions().minRaiseTo()).hasValue(140);
        assertThat(hand.legalActions().maxRaiseTo()).isEqualTo(1_000);
    }

    @Test
    void oneActivePlayerAndMultipleAllInsRunOutWithoutEmptyStreetActions() {
        PlayerStack button = player(101, 5, 1_000);
        PlayerStack smallBlind = player(102, 0, 100);
        PlayerStack bigBlind = player(103, 2, 200);
        Hand hand = Hand.start(
                        hand(11),
                        List.of(button, smallBlind, bigBlind),
                        5,
                        BLINDS,
                        Deck.ordered(fixedCardsForThreePlayers()))
                .hand();

        hand = hand.act(button.playerId(), PlayerAction.call()).hand();
        hand = hand.act(smallBlind.playerId(), PlayerAction.allIn(100)).hand();
        hand = hand.act(bigBlind.playerId(), PlayerAction.allIn(200)).hand();
        HandTransition buttonCall = hand.act(button.playerId(), PlayerAction.call());

        Hand completed = buttonCall.hand();
        assertThat(completed.isComplete()).isTrue();
        assertThat(completed.actor()).isNull();
        assertThat(completed.legalActions().types()).isEmpty();
        assertThat(buttonCall.events())
                .filteredOn(HandEvent.CommunityCardsDealt.class::isInstance)
                .map(HandEvent.CommunityCardsDealt.class::cast)
                .extracting(event -> event.cards().size())
                .containsExactly(3, 1, 1);
        assertThat(seat(completed, smallBlind.playerId()).stack()).isEqualTo(300);
        assertThat(seat(completed, bigBlind.playerId()).stack()).isEqualTo(200);
        assertThat(seat(completed, button.playerId()).stack()).isEqualTo(800);
        assertThat(completed.totalChipsInSystem()).isEqualTo(1_300);
    }

    @Test
    void startRejectsInvalidTablesInsufficientDecksDuplicatesAndChipOverflow() {
        PlayerStack first = player(111, 0, 100);
        PlayerStack second = player(112, 1, 100);
        PlayerStack duplicateId = new PlayerStack(first.playerId(), 2, 100);
        PlayerStack duplicateSeat = player(113, 0, 100);
        List<Card> headsUpCards = fixedCardsForHeadsUp();

        assertThatIllegalArgumentException().isThrownBy(() -> Hand.start(
                hand(12), List.of(first), 0, BLINDS, Deck.ordered(headsUpCards)));
        assertThatIllegalArgumentException().isThrownBy(() -> Hand.start(
                hand(12), List.of(first, second, duplicateId), 0, BLINDS,
                Deck.ordered(fixedCardsForThreePlayers())));
        assertThatIllegalArgumentException().isThrownBy(() -> Hand.start(
                hand(12), List.of(first, second, duplicateSeat), 0, BLINDS,
                Deck.ordered(fixedCardsForThreePlayers())));
        assertThatIllegalArgumentException().isThrownBy(() -> Hand.start(
                hand(12), List.of(first, second), 2, BLINDS, Deck.ordered(headsUpCards)));
        assertThatIllegalArgumentException().isThrownBy(() -> Hand.start(
                hand(12), List.of(first, second), 0, BLINDS,
                Deck.ordered(headsUpCards.subList(0, headsUpCards.size() - 1))));
        assertThatIllegalArgumentException().isThrownBy(() -> Deck.ordered(List.of(
                card(ACE, SPADES), card(ACE, SPADES))));
        assertThatThrownBy(() -> Hand.start(
                        hand(12),
                        List.of(player(114, 0, Long.MAX_VALUE), player(115, 1, Long.MAX_VALUE)),
                        0,
                        BLINDS,
                        Deck.ordered(headsUpCards)))
                .isInstanceOf(ArithmeticException.class);

        assertThatIllegalArgumentException().isThrownBy(() -> new BlindLevel(0, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new BlindLevel(2, 3));
        assertThatThrownBy(() -> new BlindLevel(Long.MAX_VALUE / 2 + 1, Long.MAX_VALUE))
                .isInstanceOf(ArithmeticException.class);
        assertThatIllegalArgumentException().isThrownBy(() -> player(116, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> player(117, 6, 1));
    }

    @Test
    void startAcceptsSixFundedSeatsAndRejectsASeventhPlayer() {
        List<PlayerStack> sixPlayers = List.of(
                player(131, 0, 1_000),
                player(132, 1, 1_000),
                player(133, 2, 1_000),
                player(134, 3, 1_000),
                player(135, 4, 1_000),
                player(136, 5, 1_000));

        Hand sixHand = Hand.start(
                        hand(14), sixPlayers, 5, BLINDS, Deck.standard())
                .hand();

        assertThat(sixHand.seats()).hasSize(6);
        assertThat(sixHand.actor().seatIndex()).isEqualTo(2);
        assertThatIllegalArgumentException().isThrownBy(() -> Hand.start(
                hand(15),
                new ArrayList<>(List.of(
                        sixPlayers.get(0),
                        sixPlayers.get(1),
                        sixPlayers.get(2),
                        sixPlayers.get(3),
                        sixPlayers.get(4),
                        sixPlayers.get(5),
                        player(137, 0, 1_000))),
                5,
                BLINDS,
                Deck.standard()));
    }

    @Test
    void startAndHandAccessorsDefensivelyCopyEveryCollection() {
        PlayerStack first = player(121, 0, 1_000);
        PlayerStack second = player(122, 1, 1_000);
        List<PlayerStack> players = new ArrayList<>(List.of(first, second));

        HandTransition transition = Hand.start(
                hand(13), players, 0, BLINDS, Deck.ordered(fixedCardsForHeadsUp()));
        players.clear();
        Hand hand = transition.hand();

        assertThat(hand.seats()).hasSize(2);
        assertThat(hand.holeCards(first.playerId())).hasSize(2);
        assertThatThrownBy(() -> hand.seats().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> hand.board().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> hand.holeCards(first.playerId()).clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> transition.events().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static SeatState seat(Hand hand, PlayerId playerId) {
        return hand.seats().stream()
                .filter(seat -> seat.playerId().equals(playerId))
                .findFirst()
                .orElseThrow();
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
