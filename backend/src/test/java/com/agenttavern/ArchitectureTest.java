package com.agenttavern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.NamedInterface;

class ArchitectureTest {

    @Test
    void applicationModulesAreValid() {
        ApplicationModules.of(AgentTavernApplication.class).verify();
    }

    @Test
    void applicationModulesDeclareTournamentAndPersistenceBoundaries() {
        ApplicationModules modules = ApplicationModules.of(AgentTavernApplication.class);
        ApplicationModule game = moduleNamed(modules, "game");
        ApplicationModule tournament = moduleNamed(modules, "tournament");

        assertThat(modules.stream().map(ApplicationModule::getIdentifier))
                .extracting(Object::toString)
                .containsExactlyInAnyOrder(
                        "game", "tournament", "persistence", "agents", "tablechat", "llmprovider", "web");
        assertThat(game.isOpen()).isFalse();
        assertThat(game.getNamedInterfaces().stream()
                .filter(NamedInterface::isNamed)
                .map(NamedInterface::getName))
                .containsExactlyInAnyOrder("betting", "card", "hand", "handvalue");
        assertThat(tournament.getAllowedDependencies(modules).stream())
                .extracting(
                        dependency -> dependency.getTargetModule().getIdentifier().toString(),
                        dependency -> dependency.getTargetNamedInterface().getName())
                .containsExactlyInAnyOrder(
                        tuple("game", "betting"),
                        tuple("game", "card"),
                        tuple("game", "hand"));
        assertThat(moduleNamed(modules, "agents").getAllowedDependencies(modules).stream())
                .extracting(
                        dependency -> dependency.getTargetModule().getIdentifier().toString(),
                        dependency -> dependency.getTargetNamedInterface().getName())
                .containsExactlyInAnyOrder(tuple("game", "betting"), tuple("game", "card"), tuple("game", "handvalue"));
        assertThat(moduleNamed(modules, "tablechat").getAllowedDependencies(modules).stream())
                .extracting(
                        dependency -> dependency.getTargetModule().getIdentifier().toString(),
                        dependency -> dependency.getTargetNamedInterface().getName())
                .containsExactlyInAnyOrder(tuple("game", "betting"), tuple("tournament", "<<UNNAMED>>"));
        assertThat(moduleNamed(modules, "llmprovider").getAllowedDependencies(modules).toString())
                .contains("agents", "tablechat", "game :: betting", "game :: card");
        assertThat(moduleNamed(modules, "persistence").getAllowedDependencies(modules).toString())
                .contains("tournament", "tournament :: port", "game :: betting", "game :: hand");
        assertThat(moduleNamed(modules, "web").getAllowedDependencies(modules).toString())
                .contains("agents", "tablechat", "tournament", "tournament :: application",
                        "tournament :: port", "game :: betting", "game :: card", "game :: hand");
    }

    private static ApplicationModule moduleNamed(ApplicationModules modules, String name) {
        return modules.getModuleByName(name).orElseThrow();
    }
}
