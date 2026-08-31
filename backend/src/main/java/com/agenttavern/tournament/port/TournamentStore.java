package com.agenttavern.tournament.port;

import com.agenttavern.tournament.TournamentEventEnvelope;
import com.agenttavern.tournament.TournamentId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Transactional persistence boundary for checkpoints, event streams, and command receipts. */
public interface TournamentStore {

    Optional<StoredTournament> load(TournamentId tournamentId);

    Optional<TournamentWriteResult> findCommand(TournamentId tournamentId, UUID commandId);

    TournamentWriteResult commit(TournamentCommit commit);

    List<TournamentEventEnvelope> eventsAfter(TournamentId tournamentId, long sequenceExclusive);
}
