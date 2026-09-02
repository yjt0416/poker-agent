package com.agenttavern.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.betting.PlayerAction;
import com.agenttavern.game.betting.PlayerStatus;
import com.agenttavern.game.betting.SeatState;
import com.agenttavern.game.betting.Street;
import com.agenttavern.game.card.Card;
import com.agenttavern.game.card.Deck;
import com.agenttavern.game.card.Rank;
import com.agenttavern.game.card.Suit;
import com.agenttavern.game.hand.BlindLevel;
import com.agenttavern.game.hand.HandEvent;
import com.agenttavern.game.hand.HandId;
import com.agenttavern.game.hand.PlayerStack;
import com.agenttavern.game.showdown.Pot;
import com.agenttavern.tournament.Tournament;
import com.agenttavern.tournament.TournamentCheckpoint;
import com.agenttavern.tournament.TournamentEntrant;
import com.agenttavern.tournament.TournamentEvent;
import com.agenttavern.tournament.TournamentId;
import com.agenttavern.tournament.TournamentMode;
import com.agenttavern.tournament.TournamentSeat;
import com.agenttavern.tournament.TournamentSeatStatus;
import com.agenttavern.tournament.TournamentTransition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TournamentEventJsonCodecTest {

    private final TournamentCheckpointJdbcMapper checkpointMapper =
            new TournamentCheckpointJdbcMapper(TournamentCheckpointJdbcMapper.jsonMapper());
    private final TournamentEventJsonCodec codec =
            new TournamentEventJsonCodec(TournamentCheckpointJdbcMapper.jsonMapper());

    @ParameterizedTest
    @MethodSource("allEventVariants")
    void everyEventVariantRoundTrips(TournamentEvent event) {
        TournamentEventJsonCodec.EncodedEvent encoded = codec.encode(event);

        assertThat(codec.decode(encoded.eventType(), encoded.payloadJson())).isEqualTo(event);
    }

    @ParameterizedTest
    @MethodSource("representativeCheckpoints")
    void representativeCheckpointStatesRoundTrip(TournamentCheckpoint checkpoint) {
        String json = checkpointMapper.checkpointJson(checkpoint);

        assertThat(checkpointMapper.checkpointFromJson(json)).isEqualTo(checkpoint);
    }

    private static Stream<TournamentEvent> allEventVariants() {
        TournamentTransition started = startedTournament();
        TournamentId tournamentId = started.tournament().id();
        PlayerId winner = player(1);
        List<TournamentSeat> finalSeats = List.of(
                new TournamentSeat(winner, 0, 60_000, TournamentSeatStatus.WINNER, 1),
                new TournamentSeat(player(2), 1, 0, TournamentSeatStatus.ELIMINATED, 2),
                new TournamentSeat(player(3), 2, 0, TournamentSeatStatus.ELIMINATED, 3),
                new TournamentSeat(player(4), 3, 0, TournamentSeatStatus.ELIMINATED, 4),
                new TournamentSeat(player(5), 4, 0, TournamentSeatStatus.ELIMINATED, 5),
                new TournamentSeat(player(6), 5, 0, TournamentSeatStatus.ELIMINATED, 6));
        return Stream.of(
                started.events().getFirst(),
                new TournamentEvent.HandEventRecorded(tournamentId, new HandEvent.HandStarted(
                        hand(2),
                        List.of(new PlayerStack(player(1), 0, 1_000), new PlayerStack(player(2), 1, 1_000)),
                        0,
                        new BlindLevel(50, 100))),
                new TournamentEvent.HandEventRecorded(tournamentId, new HandEvent.HoleCardsDealt(
                        hand(2), Map.of(
                                player(1), List.of(card(Suit.CLUBS, Rank.TWO), card(Suit.DIAMONDS, Rank.THREE)),
                                player(2), List.of(card(Suit.HEARTS, Rank.FOUR), card(Suit.SPADES, Rank.FIVE))))),
                new TournamentEvent.HandEventRecorded(tournamentId, new HandEvent.BlindsPosted(
                        hand(2), player(1), 0, 50, player(2), 1, 100)),
                new TournamentEvent.HandEventRecorded(tournamentId, new HandEvent.PlayerActed(
                        hand(2), player(1), 0, Street.PREFLOP, PlayerAction.call())),
                new TournamentEvent.HandEventRecorded(tournamentId, new HandEvent.CommunityCardsDealt(
                        hand(2), Street.FLOP, List.of(
                                card(Suit.CLUBS, Rank.SIX), card(Suit.DIAMONDS, Rank.SEVEN),
                                card(Suit.HEARTS, Rank.EIGHT)))),
                new TournamentEvent.HandEventRecorded(tournamentId, new HandEvent.PotsAwarded(
                        hand(2), List.of(new Pot(100, Set.of(player(1)))), Map.of(player(1), 100L))),
                new TournamentEvent.HandEventRecorded(tournamentId, new HandEvent.HandCompleted(
                        hand(2), List.of(
                                new SeatState(player(1), 0, 1_000, 0, 0, PlayerStatus.ACTIVE),
                                new SeatState(player(2), 1, 1_000, 0, 0, PlayerStatus.ACTIVE)))),
                new TournamentEvent.PlayerEliminated(tournamentId, player(6), 6),
                new TournamentEvent.BlindLevelAdvanced(
                        tournamentId, 1, new BlindLevel(75, 150)),
                new TournamentEvent.TournamentCompleted(tournamentId, winner, finalSeats));
    }

    private static Stream<TournamentCheckpoint> representativeCheckpoints() {
        Tournament tournament = startedTournament().tournament();
        TournamentCheckpoint inHand = tournament.checkpoint();
        TournamentCheckpoint betweenHands = completeByFolding(tournament).checkpoint();
        TournamentCheckpoint complete = new TournamentCheckpoint(
                tournament(2), TournamentMode.PLAYER, com.agenttavern.tournament.TournamentStatus.COMPLETE,
                List.of(
                        new TournamentSeat(player(1), 0, 60_000, TournamentSeatStatus.WINNER, 1),
                        new TournamentSeat(player(2), 1, 0, TournamentSeatStatus.ELIMINATED, 2),
                        new TournamentSeat(player(3), 2, 0, TournamentSeatStatus.ELIMINATED, 3),
                        new TournamentSeat(player(4), 3, 0, TournamentSeatStatus.ELIMINATED, 4),
                        new TournamentSeat(player(5), 4, 0, TournamentSeatStatus.ELIMINATED, 5),
                        new TournamentSeat(player(6), 5, 0, TournamentSeatStatus.ELIMINATED, 6)),
                0, 0, 0, java.util.Optional.empty(), Map.of());
        return Stream.of(inHand, betweenHands, complete);
    }

    private static TournamentTransition startedTournament() {
        return Tournament.start(
                tournament(1),
                TournamentMode.PLAYER,
                List.of(
                        entrant(1, 0), entrant(2, 1), entrant(3, 2),
                        entrant(4, 3), entrant(5, 4), entrant(6, 5)),
                2,
                hand(1),
                Deck.standard());
    }

    private static Tournament completeByFolding(Tournament tournament) {
        while (tournament.status() == com.agenttavern.tournament.TournamentStatus.IN_HAND) {
            tournament = tournament.act(
                    tournament.currentHand().actor().playerId(),
                    com.agenttavern.game.betting.PlayerAction.fold()).tournament();
        }
        return tournament;
    }

    private static TournamentEntrant entrant(long player, int seat) {
        return new TournamentEntrant(player(player), seat);
    }

    private static PlayerId player(long value) {
        return new PlayerId(new UUID(0, value));
    }

    private static HandId hand(long value) {
        return new HandId(new UUID(1, value));
    }

    private static TournamentId tournament(long value) {
        return new TournamentId(new UUID(2, value));
    }

    private static Card card(Suit suit, Rank rank) {
        return new Card(suit, rank);
    }
}
