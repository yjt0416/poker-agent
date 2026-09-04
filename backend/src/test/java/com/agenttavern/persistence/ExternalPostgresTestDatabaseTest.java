package com.agenttavern.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExternalPostgresTestDatabaseTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "jdbc:postgresql://127.0.0.1:55439/poker_it_20260904",
        "jdbc:postgresql://127.0.0.1:55439/poker_test",
        "jdbc:postgresql://localhost:55439/test_poker"
    })
    void acceptsClearlyTestOnlyLoopbackDatabases(String url) {
        assertThatCode(() -> ExternalPostgresTestDatabase.validate(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "jdbc:postgresql://127.0.0.1:55439/contest",
        "jdbc:postgresql://127.0.0.1:55439/latest",
        "jdbc:postgresql://127.0.0.1:55439/prod",
        "jdbc:postgresql://database.example:55439/poker_test",
        "jdbc:postgresql://127.0.0.1,localhost:55439/poker_test",
        "jdbc:postgresql://127.0.0.1:55439/poker_test?host=database.example"
    })
    void rejectsNonTestOrUnsafeEndpointsBeforeAnyConnection(String url) {
        assertThatThrownBy(() -> ExternalPostgresTestDatabase.validate(url))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
