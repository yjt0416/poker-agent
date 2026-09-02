package com.agenttavern.persistence;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Wires persistence only after JDBC infrastructure exists, keeping DB-free application tests isolated. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(JdbcClient.class)
public class PersistenceConfiguration {

    @Bean
    JsonMapper tournamentPersistenceJsonMapper() {
        return TournamentCheckpointJdbcMapper.jsonMapper();
    }

    @Bean
    TournamentCheckpointJdbcMapper tournamentCheckpointJdbcMapper(JsonMapper tournamentPersistenceJsonMapper) {
        return new TournamentCheckpointJdbcMapper(tournamentPersistenceJsonMapper);
    }

    @Bean
    TournamentEventJsonCodec tournamentEventJsonCodec(JsonMapper tournamentPersistenceJsonMapper) {
        return new TournamentEventJsonCodec(tournamentPersistenceJsonMapper);
    }

    @Bean
    JdbcTournamentStore jdbcTournamentStore(
            JdbcClient jdbcClient,
            TournamentCheckpointJdbcMapper tournamentCheckpointJdbcMapper,
            TournamentEventJsonCodec tournamentEventJsonCodec) {
        return new JdbcTournamentStore(jdbcClient, tournamentCheckpointJdbcMapper, tournamentEventJsonCodec);
    }
}
