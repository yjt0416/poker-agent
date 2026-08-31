package com.agenttavern.tournament;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class TournamentDependencyTest {

    @Test
    void tournamentHasNoInfrastructureDependencies() {
        tournamentInfrastructureDependencyRule()
                .check(new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.agenttavern.tournament"));
    }

    @Test
    void tournamentRuleRejectsPostgresDependencies() {
        assertThatThrownBy(() -> tournamentInfrastructureDependencyRule()
                .check(new ClassFileImporter().importClasses(PostgresDependentTournamentFixture.class)))
                .isInstanceOf(AssertionError.class);
    }

    private static ArchRule tournamentInfrastructureDependencyRule() {
        return noClasses().that().resideInAPackage("com.agenttavern.tournament..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.jdbc..", "java.sql..", "javax.sql..",
                        "org.flywaydb..", "tools.jackson..", "com.fasterxml.jackson..",
                        "jakarta.persistence..", "org.springframework.web..",
                        "org.springframework.ai..", "org.json..", "com.google.gson..",
                        "org.postgresql..", "org.hibernate..", "com.zaxxer.hikari..",
                        "io.r2dbc..");
    }

    private static final class PostgresDependentTournamentFixture {
        private org.postgresql.Driver driver;
    }
}
