package com.agenttavern.web;

import com.agenttavern.AgentTavernApplication;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** A fresh Spring context must recover the web archive as well as the domain checkpoint. */
@Testcontainers(disabledWithoutDocker = true)
class PostgresWebSessionIT extends PostgresWebSessionContract {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Override String jdbcUrl() { return postgres.getJdbcUrl(); }
    @Override String username() { return postgres.getUsername(); }
    @Override String password() { return postgres.getPassword(); }
}

abstract class PostgresWebSessionContract {
    abstract String jdbcUrl();
    abstract String username();
    abstract String password();

    ConfigurableApplicationContext application() {
        return new SpringApplicationBuilder(AgentTavernApplication.class).web(WebApplicationType.NONE)
                .run("--spring.profiles.active=postgres", "--agent-tavern.agent-provider=local",
                        "--spring.datasource.url=" + jdbcUrl(),
                        "--spring.datasource.username=" + username(),
                        "--spring.datasource.password=" + password());
    }

    @Test
    void archiveFailureRollsBackCheckpointReceiptMetadataAndFrameTogether() {
        try (var app = application()) {
            var service = app.getBean(GameTableService.class);
            var created = service.createTable("事务验收", "PLAYER", null);
            String token = created.sessionToken();
            await().atMost(Duration.ofSeconds(10)).until(() -> java.util.Objects.equals(service.current(token).actorSeat(), 5));
            var before = service.current(token);
            var archive = app.getBean(com.agenttavern.web.port.TableSessionStore.class);
            var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
            var failingArchive = new com.agenttavern.web.port.TableSessionStore() {
                public java.util.Optional<Session> find(String hash) { return archive.find(hash); }
                public java.util.List<Frame> frames(String hash,long after,int limit) { return archive.frames(hash,after,limit); }
                public java.util.List<UUID> purgeExpired(java.time.Instant cutoff) { return archive.purgeExpired(cutoff); }
                public void save(Session session,long expected,Frame frame) {
                    archive.save(session,expected,frame);
                    if (fail.get()) throw new IllegalStateException("injected archive failure");
                }
            };
            var store = app.getBean(com.agenttavern.tournament.port.TournamentStore.class);
            var faulty = new GameTableService(app.getBean(com.agenttavern.tournament.application.TournamentCommandService.class),
                    app.getBean(com.agenttavern.agents.AgentDecisionProvider.class), store, failingArchive,
                    app.getBean(tools.jackson.databind.json.JsonMapper.class), app.getBean(java.time.Clock.class),
                    app.getBeanProvider(org.springframework.transaction.PlatformTransactionManager.class),
                    app.getBean(TableStreamHub.class), app.getBean(java.util.random.RandomGenerator.class));
            var id = new com.agenttavern.tournament.TournamentId(UUID.fromString(before.tableId()));
            var stored = store.load(id).orElseThrow();
            var action = new PlayerActionRequest(before.legalActions().types().contains("CHECK") ? "CHECK" : "CALL",
                    null,UUID.randomUUID(),before.tableId(),before.version());
            try {
                assertThatThrownBy(() -> faulty.act(token, action)).hasMessage("injected archive failure");
                assertThat(store.load(id).orElseThrow()).isEqualTo(stored);
                assertThat(store.findCommand(id, action.commandId())).isEmpty();
                assertThat(faulty.current(token)).isEqualTo(before);
                assertThat(faulty.replay(token,before.tableId(),before.sequence()).frames()).isEmpty();
            } finally { faulty.close(); }
            // A new service must also observe the rolled-back metadata, not just the old runtime cache.
            var metadata = app.getBean(org.springframework.jdbc.core.simple.JdbcClient.class)
                    .sql("select sequence from table_sessions where tournament_id=:id")
                    .param("id", id.value()).query(Long.class).single();
            assertThat(metadata).isEqualTo(before.sequence());
            deleteOwnedTable(app, before.tableId());
        }
    }

    @Test
    void privateMemoryIsRestoredFromPostgresAndPassedToTheNextDecision() {
        String token;
        TableView saved;
        try(var first=application()) {
            var service=first.getBean(GameTableService.class);
            var created=service.createTable("", "SPECTATOR", null);token=created.sessionToken();
            service.advanceSpectator(token,new TableCommand(UUID.randomUUID(),created.view().tableId(),created.view().version()));
            await().atMost(Duration.ofSeconds(10)).until(()->service.current(token).version()>created.view().version());
            saved=service.current(token);
            var jdbc=first.getBean(org.springframework.jdbc.core.simple.JdbcClient.class);
            // Seed an explicit private model note for each seat, to verify the real JSONB restore path.
            jdbc.sql("""
                update table_sessions set metadata=jsonb_set(metadata,'{memories}',
                    (select jsonb_object_agg(key,jsonb_set(value,'{notes}','["private-pg-memory"]'::jsonb))
                     from jsonb_each(metadata->'memories')))
                where tournament_id=:id
                """).param("id",UUID.fromString(saved.tableId())).update();
        }
        try(var restarted=application()) {
            var observations=new java.util.concurrent.LinkedBlockingQueue<com.agenttavern.agents.AgentObservation>();
            var service=new GameTableService(restarted.getBean(com.agenttavern.tournament.application.TournamentCommandService.class),
                    o->{observations.add(o);return com.agenttavern.agents.AgentDecisionPolicy.fallback(o.legalActions(),"验收");},
                    restarted.getBean(com.agenttavern.tournament.port.TournamentStore.class),
                    restarted.getBean(com.agenttavern.web.port.TableSessionStore.class),
                    restarted.getBean(tools.jackson.databind.json.JsonMapper.class),restarted.getBean(java.time.Clock.class),
                    restarted.getBeanProvider(org.springframework.transaction.PlatformTransactionManager.class),
                    restarted.getBean(TableStreamHub.class),restarted.getBean(java.util.random.RandomGenerator.class));
            try {
                assertThat(service.current(token)).isEqualTo(saved);
                service.advanceSpectator(token,new TableCommand(UUID.randomUUID(),saved.tableId(),saved.version()));
                await().atMost(Duration.ofSeconds(10)).until(()->!observations.isEmpty());
                assertThat(observations.element().memory().notes()).contains("private-pg-memory");
                assertThat(observations.element().memory().opponents()).isNotEmpty();
                await().atMost(Duration.ofSeconds(10)).until(()->service.current(token).version()>saved.version());
                assertThat(restarted.getBean(tools.jackson.databind.json.JsonMapper.class)
                        .writeValueAsString(service.replay(token,saved.tableId(),0))).doesNotContain("private-pg-memory");
            } finally { service.close(); }
            deleteOwnedTable(restarted,saved.tableId());
        }
    }

    @Test
    void freshApplicationRestoresCookieChatLogReplayAndCommandReceipt() {
        String token;
        TableView saved;
        TableTalkRequest talk;
        try (var first = application()) {
            var service = first.getBean(GameTableService.class);
            var created = service.createTable("重启验收", "PLAYER", null);
            token = created.sessionToken();
            await().atMost(Duration.ofSeconds(10)).until(() -> java.util.Objects.equals(service.current(token).actorSeat(), 5));
            var current = service.current(token);
            talk = new TableTalkRequest("这句话应当在服务重启后保留。", UUID.randomUUID(), current.tableId(), current.version());
            saved = service.talk(token, talk);
        }
        try (var restarted = application()) {
            var service = restarted.getBean(GameTableService.class);
            assertThat(service.current(token)).isEqualTo(saved);
            assertThat(service.talk(token, talk)).isEqualTo(saved);
            var replay = service.replay(token, saved.tableId(), 0);
            assertThat(replay.frames()).last().isEqualTo(saved);
            assertThat(replay.frames()).allSatisfy(frame -> assertThat(frame.holeCards()).hasSize(2));
            assertThat(saved.chat()).anySatisfy(message -> assertThat(message.text()).isEqualTo(talk.text()));
            // This runner may target a shared test DB: delete only this test's tournament.
            deleteOwnedTable(restarted, saved.tableId());
        }
    }

    @Test
    void expiredSessionPurgeRemovesReplayAndPrivateTournamentData() {
        try (var app = application()) {
            var service = app.getBean(GameTableService.class);
            var created = service.createTable("清理验收", "PLAYER", null);
            var id = UUID.fromString(created.view().tableId());
            await().atMost(Duration.ofSeconds(10)).until(() ->
                    java.util.Objects.equals(service.current(created.sessionToken()).actorSeat(), 5));
            var jdbc = app.getBean(org.springframework.jdbc.core.simple.JdbcClient.class);
            String hash = jdbc.sql("select token_hash from table_sessions where tournament_id=:id")
                    .param("id", id).query(String.class).single();
            jdbc.sql("update table_sessions set expires_at='1970-01-01T00:00:00Z' where tournament_id=:id")
                    .param("id", id).update();
            var sessions = app.getBean(com.agenttavern.web.port.TableSessionStore.class);
            assertThat(sessions.purgeExpired(java.time.Instant.parse("1970-01-02T00:00:00Z")))
                    .contains(id);
            for (String table : java.util.List.of("table_sessions", "table_frames", "tournaments",
                    "tournament_seats", "hand_snapshots", "tournament_events", "tournament_command_receipts")) {
                if (table.equals("table_frames")) {
                    assertThat(jdbc.sql("select count(*) from table_frames where token_hash=:hash")
                            .param("hash", hash).query(Long.class).single()).isZero();
                } else if (table.equals("table_sessions")) {
                    assertThat(jdbc.sql("select count(*) from table_sessions where tournament_id=:id")
                            .param("id", id).query(Long.class).single()).isZero();
                } else {
                    assertThat(jdbc.sql("select count(*) from " + table + " where tournament_id=:id")
                            .param("id", id).query(Long.class).single()).isZero();
                }
            }
        }
    }

    private void deleteOwnedTable(ConfigurableApplicationContext app, String id) {
        var jdbc = app.getBean(org.springframework.jdbc.core.simple.JdbcClient.class);
        jdbc.sql("delete from table_sessions where tournament_id=:id").param("id", UUID.fromString(id)).update();
        jdbc.sql("delete from tournaments where tournament_id=:id").param("id", UUID.fromString(id)).update();
    }
}
