package com.agenttavern.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenttavern.game.betting.ActionType;
import com.agenttavern.game.betting.LegalActions;
import com.agenttavern.game.betting.PlayerAction;
import com.agenttavern.game.card.Deck;
import com.agenttavern.game.hand.HandId;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.Test;

class TournamentInvariantProperties {

    private static final long[] SEEDS = {2026083101L, 2026083102L, 2026083103L,
            2026083104L, 2026083105L, 2026083106L};
    private static final int COMMANDS_PER_SEED = 500;
    private static final int HARD_COMMAND_CAP = 2_000;
    private static final RandomGeneratorFactory<RandomGenerator> RANDOM_FACTORY =
            RandomGeneratorFactory.of("L64X128MixRandom");

    @Test
    void sixFixedSeedsPreserveTournamentInvariantsAndRecoveryDeterminism() {
        for (long seed : SEEDS) {
            playSeed(seed);
        }
    }

    private static void playSeed(long seed) {
        RandomGenerator random = RANDOM_FACTORY.create(seed);
        int tournamentNumber = 0;
        Tournament tournament = startTournament(seed, tournamentNumber, random);
        int command = 0;
        int operations = 0;
        while (command < COMMANDS_PER_SEED) {
            String diagnostic = "seed=%s tournament=%s command=%s checkpoint=%s".formatted(
                    seed, tournamentNumber, command, tournament.checkpoint());
            assertThat(operations + 1).as(diagnostic).isLessThan(HARD_COMMAND_CAP);
            assertInvariants(tournament, diagnostic);

            Tournament restored = Tournament.restore(tournament.checkpoint());
            assertThat(restored.checkpoint()).as(diagnostic).isEqualTo(tournament.checkpoint());

            if (tournament.status() == TournamentStatus.COMPLETE) {
                tournamentNumber++;
                tournament = startTournament(seed, tournamentNumber, random);
                operations++;
                continue;
            }

            if (tournament.status() == TournamentStatus.BETWEEN_HANDS) {
                HandId handId = new HandId(new UUID(seed,
                        Math.addExact(Math.multiplyExact((long) tournamentNumber, 10_000L), command + 3L)));
                Deck deck = Deck.standard().shuffled(random);
                TournamentTransition original = tournament.startNextHand(handId, deck);
                TournamentTransition resumed = restored.startNextHand(handId, deck);
                assertThat(resumed.events()).as(diagnostic).isEqualTo(original.events());
                assertThat(resumed.tournament().checkpoint()).as(diagnostic)
                        .isEqualTo(original.tournament().checkpoint());
                tournament = original.tournament();
            } else {
                PlayerAction action = randomLegalAction(tournament.currentHand().legalActions(), random);
                var actor = tournament.currentHand().actor().playerId();
                TournamentTransition original = tournament.act(actor, action);
                TournamentTransition resumed = restored.act(actor, action);
                assertThat(resumed.events()).as(diagnostic).isEqualTo(original.events());
                assertThat(resumed.tournament().checkpoint()).as(diagnostic)
                        .isEqualTo(original.tournament().checkpoint());
                tournament = original.tournament();
            }
            command++;
            operations++;
        }
        assertThat(command).as("seed=%s did not execute the required commands".formatted(seed))
                .isEqualTo(COMMANDS_PER_SEED);
        assertThat(operations).as("seed=%s exceeded hard command cap checkpoint=%s".formatted(
                seed, tournament.checkpoint())).isLessThan(HARD_COMMAND_CAP);
        assertInvariants(tournament, "seed=%s final command=%s checkpoint=%s".formatted(
                seed, command, tournament.checkpoint()));
    }

    private static Tournament startTournament(long seed, int tournamentNumber, RandomGenerator random) {
        return Tournament.start(
                        new TournamentId(new UUID(seed, tournamentNumber + 1L)),
                        TournamentMode.PLAYER,
                        entrants(seed),
                        random.nextInt(6),
                        new HandId(new UUID(seed, Math.addExact(
                                Math.multiplyExact((long) tournamentNumber, 10_000L), 2L))),
                        Deck.standard().shuffled(random))
                .tournament();
    }

    private static void assertInvariants(Tournament tournament, String diagnostic) {
        TournamentCheckpoint checkpoint = tournament.checkpoint();
        assertThat(checkpoint.seats()).as(diagnostic).hasSize(6);
        assertThat(checkpoint.completedHands()).as(diagnostic).isGreaterThanOrEqualTo(0);
        assertThat(checkpoint.blindLevelIndex()).as(diagnostic)
                .isEqualTo(Math.min(checkpoint.completedHands() / 8, 14));
        assertThat(checkpoint.seats().stream().mapToLong(TournamentSeat::stack).sum()
                + checkpoint.currentHand().stream()
                        .flatMap(hand -> hand.seats().stream())
                        .mapToLong(seat -> seat.handCommitted())
                        .sum())
                .as(diagnostic)
                .isEqualTo(60_000L);
        if (tournament.status() == TournamentStatus.IN_HAND) {
            assertThat(tournament.currentHand().seats()).as(diagnostic)
                    .extracting(seat -> seat.playerId())
                    .containsExactlyInAnyOrderElementsOf(checkpoint.currentHandStartingStacks().keySet());
            assertThat(tournament.currentHand().seats()).as(diagnostic)
                    .extracting(seat -> seat.seatIndex())
                    .contains(tournament.buttonSeat());
            assertThat(checkpoint.seats().stream()
                    .filter(seat -> seat.seatIndex() == tournament.buttonSeat())
                    .findFirst()
                    .orElseThrow()
                    .status()).as(diagnostic).isEqualTo(TournamentSeatStatus.FUNDED);
        }
    }

    private static PlayerAction randomLegalAction(LegalActions legal, RandomGenerator random) {
        List<ActionType> types = legal.types().stream().sorted().toList();
        ActionType type = types.get(random.nextInt(types.size()));
        return switch (type) {
            case FOLD -> PlayerAction.fold();
            case CHECK -> PlayerAction.check();
            case CALL -> PlayerAction.call();
            case RAISE -> PlayerAction.raiseTo(legal.minRaiseTo().orElseThrow());
            case ALL_IN -> PlayerAction.allIn(legal.maxRaiseTo());
        };
    }

    private static List<TournamentEntrant> entrants(long seed) {
        return List.of(
                entrant(seed, 0), entrant(seed, 1), entrant(seed, 2),
                entrant(seed, 3), entrant(seed, 4), entrant(seed, 5));
    }

    private static TournamentEntrant entrant(long seed, int seatIndex) {
        return new TournamentEntrant(new com.agenttavern.game.betting.PlayerId(
                new UUID(seed, seatIndex + 10L)), seatIndex);
    }
}
