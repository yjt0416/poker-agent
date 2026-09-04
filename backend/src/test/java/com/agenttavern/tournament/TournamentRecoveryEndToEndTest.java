package com.agenttavern.tournament;

import static com.agenttavern.game.betting.ActionType.ALL_IN;
import static com.agenttavern.game.betting.ActionType.CALL;
import static com.agenttavern.game.betting.ActionType.CHECK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.agenttavern.game.betting.LegalActions;
import com.agenttavern.game.betting.PlayerAction;
import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.card.Deck;
import com.agenttavern.game.hand.HandId;
import com.agenttavern.tournament.application.ActInTournamentCommand;
import com.agenttavern.tournament.application.CreateTournamentCommand;
import com.agenttavern.tournament.application.InMemoryTournamentStore;
import com.agenttavern.tournament.application.StartNextHandCommand;
import com.agenttavern.tournament.application.TournamentCommandService;
import com.agenttavern.tournament.application.TournamentExecution;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class TournamentRecoveryEndToEndTest {

    private static final long TOTAL_CHIPS = 60_000L;
    private static final int MAX_COMMANDS = 4_000;
    private static final long DECK_CYCLE_SEED = 6_001L;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneOffset.UTC);

    private final TournamentId tournamentId = tournament(1);
    private final InMemoryTournamentStore store = new InMemoryTournamentStore();
    private long nextCommandId = 1;

    @Test
    void sixPlayersCanFinishTournamentAcrossRepeatedServiceRestarts() {
        TournamentExecution state = newService(1).create(new CreateTournamentCommand(
                commandId(), tournamentId, TournamentMode.PLAYER, sixEntrants(), 0, hand(1)));
        int commandCount = 1;
        assertRecoveredState(state);

        while (state.checkpoint().status() != TournamentStatus.COMPLETE) {
            if (commandCount >= MAX_COMMANDS) {
                fail("tournament did not terminate before command cap: " + diagnostics(state, commandCount));
            }
            TournamentCommandService restarted = newService(state.checkpoint().completedHands() + 1L);
            state = executeOneDeterministicLegalCommand(restarted, state);
            commandCount++;
            assertRecoveredState(state);
        }

        assertThat(commandCount).as("restart commands").isGreaterThan(1);
        assertThat(state.version()).isEqualTo(commandCount);
        assertThat(state.checkpoint().completedHands()).as("completed hands").isGreaterThan(1);
        assertThat(state.checkpoint().seats())
                .filteredOn(seat -> seat.status() == TournamentSeatStatus.WINNER)
                .hasSize(1);
        assertThat(state.checkpoint().seats())
                .extracting(TournamentSeat::finishPosition)
                .containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6);
    }

    private TournamentExecution executeOneDeterministicLegalCommand(
            TournamentCommandService restarted, TournamentExecution state) {
        Tournament tournament = Tournament.restore(state.checkpoint());
        if (tournament.status() == TournamentStatus.BETWEEN_HANDS) {
            return restarted.startNextHand(new StartNextHandCommand(
                    commandId(), tournamentId, state.version(), hand(tournament.completedHands() + 1L)));
        }

        assertThat(tournament.status()).isEqualTo(TournamentStatus.IN_HAND);
        return restarted.act(new ActInTournamentCommand(
                commandId(),
                tournamentId,
                state.version(),
                tournament.currentHand().actor().playerId(),
                deterministicLegalAction(tournament.currentHand().legalActions())));
    }

    private void assertRecoveredState(TournamentExecution state) {
        assertThat(totalChips(state.checkpoint()))
                .as("chip conservation after version %s", state.version())
                .isEqualTo(TOTAL_CHIPS);
        assertThat(store.eventsAfter(tournamentId, 0).stream().map(TournamentEventEnvelope::sequence))
                .containsExactly(LongStream.rangeClosed(1, state.lastSequence()).boxed().toArray(Long[]::new));
    }

    private TournamentCommandService newService(long deckNumber) {
        return new TournamentCommandService(store, FIXED_CLOCK, () -> fixedDeck(deckNumber));
    }

    private UUID commandId() {
        return new UUID(3, nextCommandId++);
    }

    private static PlayerAction deterministicLegalAction(LegalActions legalActions) {
        if (legalActions.types().contains(CHECK)) {
            return PlayerAction.check();
        }
        if (legalActions.types().contains(CALL)) {
            return PlayerAction.call();
        }
        if (legalActions.types().contains(ALL_IN)) {
            return PlayerAction.allIn(legalActions.maxRaiseTo());
        }
        throw new AssertionError("active player has no deterministic legal action: " + legalActions);
    }

    private static Deck fixedDeck(long deckNumber) {
        return Deck.standard().shuffled(new SplittableRandom(DECK_CYCLE_SEED + deckNumber));
    }

    private static long totalChips(TournamentCheckpoint checkpoint) {
        long stacks = checkpoint.seats().stream()
                .mapToLong(TournamentSeat::stack)
                .reduce(0L, Math::addExact);
        long handCommitments = checkpoint.currentHand()
                .map(hand -> hand.seats().stream()
                        .mapToLong(seat -> seat.handCommitted())
                        .reduce(0L, Math::addExact))
                .orElse(0L);
        return Math.addExact(stacks, handCommitments);
    }

    private static String diagnostics(TournamentExecution state, int commandCount) {
        TournamentCheckpoint checkpoint = state.checkpoint();
        return "commands=%d, version=%d, sequence=%d, status=%s, completedHands=%d, seats=%s"
                .formatted(
                        commandCount,
                        state.version(),
                        state.lastSequence(),
                        checkpoint.status(),
                        checkpoint.completedHands(),
                        checkpoint.seats());
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
