package com.agenttavern.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenttavern.game.betting.PlayerAction;
import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.card.Deck;
import com.agenttavern.game.hand.HandId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TournamentCheckpointTest {

    @Test
    void restoreDuringTurnPreservesNextEventsAndState() {
        Tournament original = inProgressTournament();
        PlayerId actor = original.currentHand().actor().playerId();
        PlayerAction action = PlayerAction.call();

        Tournament restored = Tournament.restore(original.checkpoint());
        TournamentTransition originalTransition = original.act(actor, action);
        TournamentTransition restoredTransition = restored.act(actor, action);

        assertThat(restored.checkpoint()).isEqualTo(original.checkpoint());
        assertThat(restoredTransition.events()).isEqualTo(originalTransition.events());
        assertThat(restoredTransition.tournament().checkpoint())
                .isEqualTo(originalTransition.tournament().checkpoint());
    }

    @Test
    void restoreRejectsHandThatIsOutOfSyncWithTournamentStacks() {
        TournamentCheckpoint source = inProgressTournament().checkpoint();
        List<TournamentSeat> changedSeats = new ArrayList<>(source.seats());
        TournamentSeat changed = changedSeats.getFirst();
        changedSeats.set(0, new TournamentSeat(
                changed.playerId(),
                changed.seatIndex(),
                changed.stack() + 1,
                changed.status(),
                changed.finishPosition()));

        assertThatThrownBy(() -> Tournament.restore(checkpointWith(
                source, changedSeats, source.currentHand(), source.currentHandStartingStacks())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stacks must match");
    }

    @Test
    void restoreRejectsStartingStacksThatDoNotExactlyMatchHandPlayers() {
        TournamentCheckpoint source = inProgressTournament().checkpoint();
        Map<PlayerId, Long> missingPlayer = new LinkedHashMap<>(source.currentHandStartingStacks());
        missingPlayer.remove(missingPlayer.keySet().iterator().next());

        assertThatThrownBy(() -> Tournament.restore(checkpointWith(
                source, source.seats(), source.currentHand(), missingPlayer)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("starting stacks must match");
    }

    @Test
    void restoreAllowsBetweenHandsButtonToRemainOnJustEliminatedSeat() {
        List<TournamentSeat> seats = List.of(
                new TournamentSeat(player(1), 0, 0, TournamentSeatStatus.ELIMINATED, 6),
                new TournamentSeat(player(2), 1, 20_000, TournamentSeatStatus.FUNDED, null),
                new TournamentSeat(player(3), 2, 10_000, TournamentSeatStatus.FUNDED, null),
                new TournamentSeat(player(4), 3, 10_000, TournamentSeatStatus.FUNDED, null),
                new TournamentSeat(player(5), 4, 10_000, TournamentSeatStatus.FUNDED, null),
                new TournamentSeat(player(6), 5, 10_000, TournamentSeatStatus.FUNDED, null));
        TournamentCheckpoint checkpoint = new TournamentCheckpoint(
                tournament(2),
                TournamentMode.PLAYER,
                TournamentStatus.BETWEEN_HANDS,
                seats,
                0,
                1,
                0,
                Optional.empty(),
                Map.of());

        Tournament restored = Tournament.restore(checkpoint);

        assertThat(restored.buttonSeat()).isZero();
        assertThat(restored.startNextHand(hand(2), Deck.standard()).tournament().buttonSeat())
                .isEqualTo(1);
    }

    @Test
    void checkpointDefensivelyCopiesSeatAndStartingStackCollections() {
        TournamentCheckpoint source = inProgressTournament().checkpoint();
        List<TournamentSeat> seats = new ArrayList<>(source.seats());
        Map<PlayerId, Long> startingStacks = new LinkedHashMap<>(source.currentHandStartingStacks());
        TournamentCheckpoint checkpoint = checkpointWith(
                source, seats, source.currentHand(), startingStacks);
        seats.clear();
        startingStacks.clear();

        assertThat(checkpoint.seats()).hasSize(6);
        assertThat(checkpoint.currentHandStartingStacks()).hasSize(6);
        assertThatThrownBy(() -> checkpoint.seats().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> checkpoint.currentHandStartingStacks().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static Tournament inProgressTournament() {
        Tournament tournament = Tournament.start(
                        tournament(1), TournamentMode.PLAYER, sixEntrants(), 2, hand(1), Deck.standard())
                .tournament();
        tournament = tournament.act(
                        tournament.currentHand().actor().playerId(), PlayerAction.call())
                .tournament();
        return tournament.act(tournament.currentHand().actor().playerId(), PlayerAction.call()).tournament();
    }

    private static TournamentCheckpoint checkpointWith(
            TournamentCheckpoint source,
            List<TournamentSeat> seats,
            Optional<com.agenttavern.game.hand.HandCheckpoint> currentHand,
            Map<PlayerId, Long> startingStacks) {
        return new TournamentCheckpoint(
                source.id(),
                source.mode(),
                source.status(),
                seats,
                source.buttonSeat(),
                source.completedHands(),
                source.blindLevelIndex(),
                currentHand,
                startingStacks);
    }

    private static List<TournamentEntrant> sixEntrants() {
        return List.of(
                entrant(1, 0), entrant(2, 1), entrant(3, 2),
                entrant(4, 3), entrant(5, 4), entrant(6, 5));
    }

    private static TournamentEntrant entrant(long playerId, int seatIndex) {
        return new TournamentEntrant(player(playerId), seatIndex);
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
