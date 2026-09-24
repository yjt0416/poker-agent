package com.agenttavern.web.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Server-only session metadata and audience-specific replay frames. Never stores raw cookies. */
public interface TableSessionStore {
    record Session(String tokenHash, UUID tournamentId, long sequence, String metadata, Instant expiresAt) {}
    record Frame(long sequence, String json) {}
    Optional<Session> find(String tokenHash);
    void save(Session session, long expectedSequence, Frame frame);
    List<Frame> frames(String tokenHash, long after, int limit);
}
