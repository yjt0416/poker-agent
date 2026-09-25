package com.agenttavern.persistence;

import com.agenttavern.web.port.TableSessionStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

public class JdbcTableSessionStore implements TableSessionStore {
    private final JdbcClient jdbc;
    public JdbcTableSessionStore(JdbcClient jdbc) { this.jdbc = jdbc; }
    public Optional<Session> find(String hash) {
        return jdbc.sql("select * from table_sessions where token_hash=:hash").param("hash", hash)
                .query((rs, row) -> new Session(rs.getString("token_hash"), rs.getObject("tournament_id", UUID.class),
                        rs.getLong("sequence"), rs.getString("metadata"),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant())).optional();
    }
    @Transactional
    public void save(Session session, long expected, Frame frame) {
        int updated;
        if (expected == 0) {
            updated = jdbc.sql("""
                    insert into table_sessions(token_hash,tournament_id,sequence,metadata,expires_at)
                    values(:hash,:id,:seq,cast(:data as jsonb),:expires) on conflict do nothing
                    """).param("hash", session.tokenHash()).param("id", session.tournamentId())
                    .param("seq", session.sequence()).param("data", session.metadata())
                    .param("expires", session.expiresAt().atOffset(ZoneOffset.UTC)).update();
        } else {
            updated = jdbc.sql("""
                    update table_sessions set sequence=:seq,metadata=cast(:data as jsonb)
                    where token_hash=:hash and sequence=:expected
                    """).param("hash", session.tokenHash()).param("seq", session.sequence())
                    .param("data", session.metadata()).param("expected", expected).update();
        }
        if (updated != 1) throw new ConcurrentModificationException("session version changed");
        jdbc.sql("insert into table_frames(token_hash,sequence,projection) values(:hash,:seq,cast(:data as jsonb))")
                .param("hash", session.tokenHash()).param("seq", frame.sequence()).param("data", frame.json()).update();
    }
    public List<Frame> frames(String hash, long after, int limit) {
        return jdbc.sql("""
                select sequence,projection from table_frames where token_hash=:hash and sequence>:after
                order by sequence limit :limit
                """).param("hash", hash).param("after", after).param("limit", limit)
                .query((rs, row) -> new Frame(rs.getLong(1), rs.getString(2))).list();
    }
    @Transactional
    public List<UUID> purgeExpired(Instant cutoff) {
        var ids = jdbc.sql("""
                with doomed as (
                    select token_hash from table_sessions where expires_at <= :cutoff
                    order by expires_at limit 500 for update skip locked
                )
                delete from table_sessions where token_hash in (select token_hash from doomed)
                returning tournament_id
                """).param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
                .query((rs, row) -> rs.getObject(1, UUID.class)).list();
        if (!ids.isEmpty()) {
            jdbc.sql("""
                    delete from tournaments t where tournament_id in (:ids)
                    and not exists (select 1 from table_sessions s where s.tournament_id=t.tournament_id)
                    """).param("ids", ids).update();
        }
        return ids;
    }
}
