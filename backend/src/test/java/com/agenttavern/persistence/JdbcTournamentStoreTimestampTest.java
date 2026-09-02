package com.agenttavern.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class JdbcTournamentStoreTimestampTest {

    @Test
    void reconstructsAnEventInstantWithoutPostgresMicrosecondLoss() {
        Instant original = Instant.parse("2026-09-02T07:50:01.123456789Z");

        Instant restored = JdbcTournamentStore.exactOccurredAt(
                original.getEpochSecond(), original.getNano());

        assertThat(restored).isEqualTo(original);
    }
}
