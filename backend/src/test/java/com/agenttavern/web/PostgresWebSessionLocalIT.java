package com.agenttavern.web;

import java.net.URI;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="POKER_TEST_DB_URL", matches=".+")
class PostgresWebSessionLocalIT extends PostgresWebSessionContract {
    @Override String jdbcUrl() {
        String value = System.getenv("POKER_TEST_DB_URL");
        if (!value.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException("Expected PostgreSQL test URL");
        URI uri = URI.create(value.substring(5));
        if (!java.util.List.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())
                || uri.getRawQuery()!=null || uri.getRawUserInfo()!=null || uri.getRawFragment()!=null
                || uri.getPath()==null || !uri.getPath().matches("/[A-Za-z0-9_-]+")
                || !uri.getPath().substring(1).matches("(?i)(?:.*[_-])?(?:test|it)(?:[_-].*)?")) {
            throw new IllegalArgumentException("Use a loopback database with a test or it name token");
        }
        return value;
    }
    @Override String username() { return System.getenv("POKER_TEST_DB_USERNAME"); }
    @Override String password() { return System.getenv("POKER_TEST_DB_PASSWORD"); }
}
