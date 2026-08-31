package com.agenttavern.tournament;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class TournamentDependencyTest {

    @Test
    void tournamentHasNoInfrastructureDependencies() {
        noClasses().that().resideInAPackage("com.agenttavern.tournament..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.jdbc..", "java.sql..", "javax.sql..",
                        "org.flywaydb..", "tools.jackson..", "com.fasterxml.jackson..",
                        "jakarta.persistence..", "org.springframework.web..")
                .check(new ClassFileImporter().importPackages("com.agenttavern.tournament"));
    }
}
