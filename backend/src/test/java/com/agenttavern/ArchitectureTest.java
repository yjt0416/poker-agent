package com.agenttavern;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

class ArchitectureTest {

    @Test
    void applicationModulesAreValid() {
        ApplicationModules.of(AgentTavernApplication.class).verify();
    }

    @Test
    void applicationModulesDeclareTournamentAndPersistenceBoundaries() {
        ApplicationModules modules = ApplicationModules.of(AgentTavernApplication.class);

        assertThat(modules.stream().map(ApplicationModule::getIdentifier))
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("game", "tournament", "persistence");
        assertThat(moduleNamed(modules, "tournament").getAllowedDependencies(modules).toString())
                .isEqualTo("game");
        assertThat(moduleNamed(modules, "persistence").getAllowedDependencies(modules).toString())
                .isEqualTo("tournament, game");
    }

    private static ApplicationModule moduleNamed(ApplicationModules modules, String name) {
        return modules.getModuleByName(name).orElseThrow();
    }
}
