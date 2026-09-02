package com.agenttavern.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationContractTest {

    @Test
    void migrationDefinesTheTournamentPersistenceContract() throws IOException {
        String sql;
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V1__create_tournament_store.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertThat(sql)
                .contains("create table tournaments")
                .contains("create table tournament_seats")
                .contains("create table hand_snapshots")
                .contains("create table tournament_events")
                .contains("create table tournament_command_receipts")
                .contains("tournament_id uuid primary key")
                .contains("checkpoint_json jsonb")
                .contains("payload_json jsonb")
                .contains("receipt_checkpoint_json jsonb")
                .contains("occurred_epoch_second bigint not null")
                .contains("occurred_nano integer not null check (occurred_nano between 0 and 999999999)")
                .contains("version >= 1")
                .contains("last_sequence >= 0")
                .contains("blind_level_index between 0 and 14")
                .contains("on delete cascade")
                .contains("unique (tournament_id, sequence)")
                .contains("unique (tournament_id, command_id)")
                .contains("create index");
    }
}
