package com.agenttavern;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ArchitectureTest {

    @Test
    void applicationModulesAreValid() {
        ApplicationModules.of(AgentTavernApplication.class).verify();
    }
}
