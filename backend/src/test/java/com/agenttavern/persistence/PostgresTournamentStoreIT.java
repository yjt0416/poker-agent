package com.agenttavern.persistence;

import com.agenttavern.AgentTavernApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Docker-backed execution of the shared PostgreSQL acceptance contract. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = AgentTavernApplication.class)
@ActiveProfiles("postgres-it")
class PostgresTournamentStoreIT extends PostgresTournamentStoreContract {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("POKER_TEST_DB_URL", postgres::getJdbcUrl);
        registry.add("POKER_TEST_DB_USERNAME", postgres::getUsername);
        registry.add("POKER_TEST_DB_PASSWORD", postgres::getPassword);
    }
}
