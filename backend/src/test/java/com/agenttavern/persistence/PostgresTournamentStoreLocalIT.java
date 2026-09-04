package com.agenttavern.persistence;

import com.agenttavern.AgentTavernApplication;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Explicit opt-in runner for a loopback PostgreSQL database whose name proves it is test-only. */
@EnabledIfEnvironmentVariable(named = "POKER_TEST_DB_URL", matches = ".+", disabledReason = "set POKER_TEST_DB_URL to opt in")
@SpringBootTest(classes = AgentTavernApplication.class)
@ActiveProfiles("postgres-it")
class PostgresTournamentStoreLocalIT extends PostgresTournamentStoreContract {

    @DynamicPropertySource
    static void validateExternalDatabase(DynamicPropertyRegistry ignored) {
        ExternalPostgresTestDatabase.validate(System.getenv("POKER_TEST_DB_URL"));
    }
}

final class ExternalPostgresTestDatabase {

    private static final Pattern TEST_DATABASE_TOKEN = Pattern.compile("(?:^|[_-])(?:test|it)(?:[_-]|$)");

    private ExternalPostgresTestDatabase() {}

    static void validate(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalStateException("POKER_TEST_DB_URL must be set for the local PostgreSQL runner");
        }
        if (!jdbcUrl.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("POKER_TEST_DB_URL must be a PostgreSQL JDBC URL");
        }
        URI endpoint;
        try {
            endpoint = new URI(jdbcUrl.substring("jdbc:".length()));
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("POKER_TEST_DB_URL is not a valid JDBC URL", exception);
        }
        if (!"postgresql".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getHost() == null
                || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null
                || !isLoopback(endpoint.getHost())) {
            throw new IllegalArgumentException(
                    "POKER_TEST_DB_URL must target one loopback PostgreSQL host without URL options");
        }
        String path = endpoint.getRawPath();
        if (path == null || !path.matches("/[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("POKER_TEST_DB_URL must name one database");
        }
        String database = path.substring(1).toLowerCase(Locale.ROOT);
        if (!TEST_DATABASE_TOKEN.matcher(database).find()) {
            throw new IllegalArgumentException(
                    "POKER_TEST_DB_URL database name must contain test or it as a separate token");
        }
    }

    private static boolean isLoopback(String host) {
        return host.equals("127.0.0.1") || host.equalsIgnoreCase("localhost") || host.equals("[::1]")
                || host.equals("::1");
    }
}
