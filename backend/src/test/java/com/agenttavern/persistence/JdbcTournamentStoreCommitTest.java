package com.agenttavern.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.card.Deck;
import com.agenttavern.game.hand.HandId;
import com.agenttavern.tournament.Tournament;
import com.agenttavern.tournament.TournamentCheckpoint;
import com.agenttavern.tournament.TournamentEntrant;
import com.agenttavern.tournament.TournamentId;
import com.agenttavern.tournament.TournamentMode;
import com.agenttavern.tournament.TournamentSeat;
import com.agenttavern.tournament.TournamentSeatStatus;
import com.agenttavern.tournament.TournamentStatus;
import com.agenttavern.tournament.port.StoredTournament;
import com.agenttavern.tournament.port.TournamentCommit;
import com.agenttavern.tournament.port.TournamentWriteResult;
import com.agenttavern.tournament.port.TournamentWriteResult.WriteStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcTournamentStoreCommitTest {

    @Test
    void rechecksTheReceiptWhenTheTournamentAdvancesAfterTheFirstReceiptMiss() {
        TournamentCheckpoint checkpoint = validCheckpoint();
        UUID commandId = UUID.randomUUID();
        TournamentWriteResult originalReceipt = new TournamentWriteResult(
                WriteStatus.ALREADY_APPLIED,
                new StoredTournament(checkpoint, 2, 0),
                List.of());
        ReceiptAppearingAfterLoadStore store = new ReceiptAppearingAfterLoadStore(
                originalReceipt, new StoredTournament(checkpoint, 2, 0));

        TournamentWriteResult result = store.commit(new TournamentCommit(
                commandId, 1, checkpoint, List.of()));

        assertThat(result).isEqualTo(originalReceipt);
        assertThat(store.receiptReads()).isEqualTo(2);
    }

    @Test
    void rejectsAnInvalidAggregateCheckpointBeforeAnyDml() {
        JdbcClient jdbcClient = mock(JdbcClient.class);
        TournamentCheckpoint valid = validCheckpoint();
        TournamentCheckpoint invalid = invalidCheckpoint(valid);
        NoReceiptStore store = new NoReceiptStore(jdbcClient, new StoredTournament(valid, 1, 0));

        assertThatThrownBy(() -> store.commit(new TournamentCommit(
                UUID.randomUUID(), 1, invalid, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blind-level index");
        verifyNoInteractions(jdbcClient);
    }

    @Test
    void returnsAnExistingReceiptBeforeValidatingItsDuplicateCheckpoint() {
        JdbcClient jdbcClient = mock(JdbcClient.class);
        TournamentCheckpoint valid = validCheckpoint();
        TournamentWriteResult receipt = new TournamentWriteResult(
                WriteStatus.ALREADY_APPLIED,
                new StoredTournament(valid, 1, 0),
                List.of());
        ExistingReceiptStore store = new ExistingReceiptStore(jdbcClient, receipt);

        TournamentWriteResult result = store.commit(new TournamentCommit(
                UUID.randomUUID(), 1, invalidCheckpoint(valid), List.of()));

        assertThat(result).isEqualTo(receipt);
        verifyNoInteractions(jdbcClient);
    }

    private static TournamentCheckpoint invalidCheckpoint(TournamentCheckpoint valid) {
        return new TournamentCheckpoint(
                valid.id(),
                valid.mode(),
                TournamentStatus.BETWEEN_HANDS,
                fundedSeats(),
                0,
                8,
                0,
                Optional.empty(),
                Map.of());
    }

    private static TournamentCheckpoint validCheckpoint() {
        return Tournament.start(
                tournament(1),
                TournamentMode.PLAYER,
                List.of(
                        entrant(1, 0), entrant(2, 1), entrant(3, 2),
                        entrant(4, 3), entrant(5, 4), entrant(6, 5)),
                2,
                hand(1),
                Deck.standard()).tournament().checkpoint();
    }

    private static List<TournamentSeat> fundedSeats() {
        return List.of(
                new TournamentSeat(player(1), 0, 10_000, TournamentSeatStatus.FUNDED, null),
                new TournamentSeat(player(2), 1, 10_000, TournamentSeatStatus.FUNDED, null),
                new TournamentSeat(player(3), 2, 10_000, TournamentSeatStatus.FUNDED, null),
                new TournamentSeat(player(4), 3, 10_000, TournamentSeatStatus.FUNDED, null),
                new TournamentSeat(player(5), 4, 10_000, TournamentSeatStatus.FUNDED, null),
                new TournamentSeat(player(6), 5, 10_000, TournamentSeatStatus.FUNDED, null));
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

    private static final class ReceiptAppearingAfterLoadStore extends JdbcTournamentStore {
        private final TournamentWriteResult receipt;
        private final StoredTournament loaded;
        private int receiptReads;

        private ReceiptAppearingAfterLoadStore(TournamentWriteResult receipt, StoredTournament loaded) {
            super(mock(JdbcClient.class), mapper(), codec());
            this.receipt = receipt;
            this.loaded = loaded;
        }

        @Override
        public Optional<TournamentWriteResult> findCommand(TournamentId tournamentId, UUID commandId) {
            return ++receiptReads == 1 ? Optional.empty() : Optional.of(receipt);
        }

        @Override
        public Optional<StoredTournament> load(TournamentId tournamentId) {
            return Optional.of(loaded);
        }

        private int receiptReads() {
            return receiptReads;
        }
    }

    private static final class NoReceiptStore extends JdbcTournamentStore {
        private final StoredTournament loaded;

        private NoReceiptStore(JdbcClient jdbcClient, StoredTournament loaded) {
            super(jdbcClient, mapper(), codec());
            this.loaded = loaded;
        }

        @Override
        public Optional<TournamentWriteResult> findCommand(TournamentId tournamentId, UUID commandId) {
            return Optional.empty();
        }

        @Override
        public Optional<StoredTournament> load(TournamentId tournamentId) {
            return Optional.of(loaded);
        }
    }

    private static final class ExistingReceiptStore extends JdbcTournamentStore {
        private final TournamentWriteResult receipt;

        private ExistingReceiptStore(JdbcClient jdbcClient, TournamentWriteResult receipt) {
            super(jdbcClient, mapper(), codec());
            this.receipt = receipt;
        }

        @Override
        public Optional<TournamentWriteResult> findCommand(TournamentId tournamentId, UUID commandId) {
            return Optional.of(receipt);
        }
    }

    private static TournamentCheckpointJdbcMapper mapper() {
        return new TournamentCheckpointJdbcMapper(TournamentCheckpointJdbcMapper.jsonMapper());
    }

    private static TournamentEventJsonCodec codec() {
        return new TournamentEventJsonCodec(TournamentCheckpointJdbcMapper.jsonMapper());
    }
}
