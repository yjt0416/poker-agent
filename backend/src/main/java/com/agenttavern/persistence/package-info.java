@org.springframework.modulith.ApplicationModule(
        displayName = "Persistence",
        allowedDependencies = {"tournament", "tournament::port", "game::betting", "game::hand"})
package com.agenttavern.persistence;
