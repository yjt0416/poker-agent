package com.agenttavern.tournament;

import static com.agenttavern.game.card.Rank.ACE;
import static com.agenttavern.game.card.Rank.EIGHT;
import static com.agenttavern.game.card.Rank.FIVE;
import static com.agenttavern.game.card.Rank.FOUR;
import static com.agenttavern.game.card.Rank.JACK;
import static com.agenttavern.game.card.Rank.KING;
import static com.agenttavern.game.card.Rank.NINE;
import static com.agenttavern.game.card.Rank.QUEEN;
import static com.agenttavern.game.card.Rank.SEVEN;
import static com.agenttavern.game.card.Rank.SIX;
import static com.agenttavern.game.card.Rank.THREE;
import static com.agenttavern.game.card.Rank.TEN;
import static com.agenttavern.game.card.Rank.TWO;
import static com.agenttavern.game.card.Suit.CLUBS;
import static com.agenttavern.game.card.Suit.DIAMONDS;
import static com.agenttavern.game.card.Suit.HEARTS;
import static com.agenttavern.game.card.Suit.SPADES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenttavern.game.betting.IllegalActionException;
import com.agenttavern.game.betting.PlayerAction;
import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.card.Card;
import com.agenttavern.game.card.Deck;
import com.agenttavern.game.hand.BlindLevel;
import com.agenttavern.game.hand.HandId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TournamentTest {

    @Test
    void startRequiresExactlySixEntrantsAtUniqueSeatIndexesZeroThroughFive() {
        List<TournamentEntrant> entrants = sixEntrants();

        assertThatIllegalArgumentException().isThrownBy(() -> Tournament.start(
                tournament(1), TournamentMode.PLAYER, entrants.subList(0, 5), 0, hand(1), Deck.standard()));
        List<TournamentEntrant> duplicateSeat = new ArrayList<>(entrants);
        duplicateSeat.set(5, new TournamentEntrant(player(7), 0));
        assertThatIllegalArgumentException().isThrownBy(() -> Tournament.start(
                tournament(2), TournamentMode.PLAYER, duplicateSeat, 0, hand(1), Deck.standard()));
        List<TournamentEntrant> duplicatePlayer = new ArrayList<>(entrants);
        duplicatePlayer.set(5, new TournamentEntrant(entrants.getFirst().playerId(), 5));
        assertThatIllegalArgumentException().isThrownBy(() -> Tournament.start(
                tournament(3), TournamentMode.PLAYER, duplicatePlayer, 0, hand(1), Deck.standard()));
    }

    @Test
    void everyEntrantStartsWithTenThousandChipsAndTheInitialHandIsRecorded() {
        TournamentTransition transition = Tournament.start(
                tournament(1), TournamentMode.PLAYER, sixEntrants(), 2, hand(1), Deck.standard());

        assertThat(transition.tournament().status()).isEqualTo(TournamentStatus.IN_HAND);
        TournamentEvent.TournamentStarted started =
                (TournamentEvent.TournamentStarted) transition.events().getFirst();
        assertThat(started.seats()).extracting(TournamentSeat::stack).containsOnly(10_000L);
        assertThat(transition.tournament().seats())
                .extracting(TournamentSeat::stack)
                .containsOnly(10_000L, 9_950L, 9_900L);
        assertThat(transition.tournament().currentHand().seats())
                .extracting(seat -> seat.stack())
                .containsExactlyElementsOf(transition.tournament().seats().stream()
                        .map(TournamentSeat::stack)
                        .toList());
        assertThat(transition.events().subList(1, transition.events().size()))
                .allMatch(TournamentEvent.HandEventRecorded.class::isInstance);

        TournamentTransition action = transition.tournament().act(
                transition.tournament().currentHand().actor().playerId(), PlayerAction.call());
        assertThat(seatAt(action.tournament(), 5).stack()).isEqualTo(9_900L);
        assertThat(seatAt(action.tournament(), 5).stack())
                .isEqualTo(handSeatAt(action.tournament(), 5).stack());
    }

    @Test
    void onlyCurrentActorCanActAndRejectedActionsDoNotCreateATransition() {
        Tournament tournament = Tournament.start(
                        tournament(1), TournamentMode.PLAYER, sixEntrants(), 2, hand(1), Deck.standard())
                .tournament();
        PlayerId actor = tournament.currentHand().actor().playerId();
        PlayerId other = tournament.seats().stream()
                .map(TournamentSeat::playerId)
                .filter(playerId -> !playerId.equals(actor))
                .findFirst()
                .orElseThrow();

        assertThatThrownBy(() -> tournament.act(other, PlayerAction.call()))
                .isInstanceOf(IllegalActionException.class)
                .hasMessage("player is not the current actor");
        assertThat(tournament.currentHand().actor().playerId()).isEqualTo(actor);
    }

    @Test
    void nextHandCannotStartBeforeCurrentHandCompletes() {
        Tournament tournament = Tournament.start(
                        tournament(1), TournamentMode.PLAYER, sixEntrants(), 2, hand(1), Deck.standard())
                .tournament();

        assertThatIllegalStateException().isThrownBy(() -> tournament.startNextHand(hand(2), Deck.standard()));
    }

    @Test
    void completedHandMovesButtonAndStartsTheNextHand() {
        Tournament tournament = Tournament.start(
                        tournament(1), TournamentMode.PLAYER, sixEntrants(), 2, hand(1), Deck.standard())
                .tournament();
        TournamentTransition completed = completeByFolding(tournament);

        assertThat(completed.tournament().status()).isEqualTo(TournamentStatus.BETWEEN_HANDS);
        assertThat(completed.tournament().completedHands()).isEqualTo(1);
        TournamentTransition next = completed.tournament().startNextHand(hand(2), Deck.standard());
        assertThat(next.tournament().buttonSeat()).isEqualTo(3);
        assertThat(next.tournament().currentBlinds()).isEqualTo(new BlindLevel(50, 100));
    }

    @Test
    void ninthHandUsesTheSecondBlindLevelAndRecordsTheAdvancementAfterHandEight() {
        Tournament tournament = Tournament.start(
                        tournament(1), TournamentMode.PLAYER, sixEntrants(), 0, hand(1), Deck.standard())
                .tournament();
        TournamentTransition eighthCompletion = null;
        for (int completedHands = 0; completedHands < 8; completedHands++) {
            eighthCompletion = completeByFolding(tournament);
            tournament = eighthCompletion.tournament();
            if (completedHands < 7) {
                tournament = tournament.startNextHand(hand(completedHands + 2L), Deck.standard()).tournament();
            }
        }

        assertThat(tournament.status()).isEqualTo(TournamentStatus.BETWEEN_HANDS);
        assertThat(tournament.completedHands()).isEqualTo(8);
        assertThat(tournament.blindLevelIndex()).isEqualTo(1);
        assertThat(eighthCompletion.events())
                .filteredOn(TournamentEvent.BlindLevelAdvanced.class::isInstance)
                .singleElement()
                .isEqualTo(new TournamentEvent.BlindLevelAdvanced(tournament(1), 1, new BlindLevel(75, 150)));
        assertThat(eighthCompletion.events().getLast())
                .isEqualTo(new TournamentEvent.BlindLevelAdvanced(tournament(1), 1, new BlindLevel(75, 150)));

        Tournament ninthHand = tournament.startNextHand(hand(9), Deck.standard()).tournament();
        assertThat(ninthHand.currentBlinds()).isEqualTo(new BlindLevel(75, 150));
    }

    @Test
    void handCompletionEliminatesZeroStacksAndExcludesThemFromTheFollowingHand() {
        Tournament tournament = Tournament.start(
                        tournament(1), TournamentMode.PLAYER, sixEntrants(), 0, hand(1), deckWhereBigBlindWins())
                .tournament();

        tournament = actCurrent(tournament, PlayerAction.allIn(10_000));
        tournament = actCurrent(tournament, PlayerAction.fold());
        tournament = actCurrent(tournament, PlayerAction.fold());
        tournament = actCurrent(tournament, PlayerAction.fold());
        tournament = actCurrent(tournament, PlayerAction.fold());
        TournamentTransition completed = tournament.act(
                tournament.currentHand().actor().playerId(), PlayerAction.call());

        assertThat(completed.tournament().status()).isEqualTo(TournamentStatus.BETWEEN_HANDS);
        TournamentSeat eliminated = seatAt(completed.tournament(), 3);
        assertThat(eliminated.status()).isEqualTo(TournamentSeatStatus.ELIMINATED);
        assertThat(eliminated.stack()).isZero();
        assertThat(eliminated.finishPosition()).isEqualTo(6);
        assertThat(completed.events())
                .filteredOn(TournamentEvent.PlayerEliminated.class::isInstance)
                .singleElement()
                .isEqualTo(new TournamentEvent.PlayerEliminated(tournament(1), player(4), 6));

        Tournament next = completed.tournament().startNextHand(hand(2), Deck.standard()).tournament();
        assertThat(next.currentHand().seats())
                .noneMatch(seat -> seat.playerId().equals(player(4)));
    }

    @Test
    void sameHandEliminationsUseClockwiseOrderLeftOfTheButtonForEqualStartingStacks() {
        Tournament tournament = Tournament.start(
                        tournament(1), TournamentMode.PLAYER, sixEntrants(), 0, hand(1), deckWhereBigBlindWins())
                .tournament();

        tournament = actCurrent(tournament, PlayerAction.allIn(10_000));
        tournament = actCurrent(tournament, PlayerAction.allIn(10_000));
        tournament = actCurrent(tournament, PlayerAction.fold());
        tournament = actCurrent(tournament, PlayerAction.fold());
        tournament = actCurrent(tournament, PlayerAction.fold());
        TournamentTransition completed = tournament.act(
                tournament.currentHand().actor().playerId(), PlayerAction.call());

        assertThat(seatAt(completed.tournament(), 3).finishPosition()).isEqualTo(6);
        assertThat(seatAt(completed.tournament(), 4).finishPosition()).isEqualTo(5);
        assertThat(completed.events())
                .filteredOn(TournamentEvent.PlayerEliminated.class::isInstance)
                .map(TournamentEvent.PlayerEliminated.class::cast)
                .extracting(TournamentEvent.PlayerEliminated::playerId)
                .containsExactly(player(4), player(5));
    }

    @Test
    void lastFundedPlayerBecomesWinnerAndCompletedTournamentCannotStartAnotherHand() {
        Tournament tournament = Tournament.start(
                        tournament(1), TournamentMode.PLAYER, sixEntrants(), 0, hand(1), deckWhereBigBlindWins())
                .tournament();

        tournament = actCurrent(tournament, PlayerAction.allIn(10_000));
        tournament = actCurrent(tournament, PlayerAction.allIn(10_000));
        tournament = actCurrent(tournament, PlayerAction.allIn(10_000));
        tournament = actCurrent(tournament, PlayerAction.allIn(10_000));
        tournament = actCurrent(tournament, PlayerAction.allIn(10_000));
        TournamentTransition completed = tournament.act(
                tournament.currentHand().actor().playerId(), PlayerAction.allIn(10_000));

        assertThat(completed.tournament().status()).isEqualTo(TournamentStatus.COMPLETE);
        assertThat(seatAt(completed.tournament(), 2).status()).isEqualTo(TournamentSeatStatus.WINNER);
        assertThat(seatAt(completed.tournament(), 2).finishPosition()).isEqualTo(1);
        assertThat(completed.events())
                .filteredOn(TournamentEvent.TournamentCompleted.class::isInstance)
                .singleElement()
                .isEqualTo(new TournamentEvent.TournamentCompleted(
                        tournament(1), player(3), completed.tournament().seats()));
        assertThatIllegalStateException().isThrownBy(
                () -> completed.tournament().startNextHand(hand(2), Deck.standard()));
    }

    private static TournamentTransition completeByFolding(Tournament tournament) {
        TournamentTransition transition = null;
        while (tournament.status() == TournamentStatus.IN_HAND) {
            transition = tournament.act(tournament.currentHand().actor().playerId(), PlayerAction.fold());
            tournament = transition.tournament();
        }
        return transition;
    }

    private static Tournament actCurrent(Tournament tournament, PlayerAction action) {
        return tournament.act(tournament.currentHand().actor().playerId(), action).tournament();
    }

    private static TournamentSeat seatAt(Tournament tournament, int seatIndex) {
        return tournament.seats().stream()
                .filter(seat -> seat.seatIndex() == seatIndex)
                .findFirst()
                .orElseThrow();
    }

    private static com.agenttavern.game.betting.SeatState handSeatAt(
            Tournament tournament, int seatIndex) {
        return tournament.currentHand().seats().stream()
                .filter(seat -> seat.seatIndex() == seatIndex)
                .findFirst()
                .orElseThrow();
    }

    private static List<TournamentEntrant> sixEntrants() {
        return List.of(
                new TournamentEntrant(player(1), 0),
                new TournamentEntrant(player(2), 1),
                new TournamentEntrant(player(3), 2),
                new TournamentEntrant(player(4), 3),
                new TournamentEntrant(player(5), 4),
                new TournamentEntrant(player(6), 5));
    }

    private static Deck deckWhereBigBlindWins() {
        return Deck.ordered(List.of(
                card(SIX, DIAMONDS), card(ACE, SPADES), card(TWO, CLUBS),
                card(FOUR, CLUBS), card(QUEEN, DIAMONDS), card(TEN, DIAMONDS),
                card(SIX, CLUBS), card(ACE, HEARTS), card(THREE, CLUBS),
                card(FIVE, CLUBS), card(QUEEN, CLUBS), card(TEN, CLUBS),
                card(FOUR, DIAMONDS), card(FIVE, DIAMONDS), card(EIGHT, HEARTS),
                card(JACK, CLUBS), card(QUEEN, HEARTS), card(KING, HEARTS),
                card(SEVEN, SPADES), card(NINE, DIAMONDS)));
    }

    private static Card card(com.agenttavern.game.card.Rank rank, com.agenttavern.game.card.Suit suit) {
        return new Card(suit, rank);
    }

    private static PlayerId player(long id) {
        return new PlayerId(new UUID(0, id));
    }

    private static HandId hand(long id) {
        return new HandId(new UUID(1, id));
    }

    private static TournamentId tournament(long id) {
        return new TournamentId(new UUID(2, id));
    }
}
