@org.springframework.modulith.ApplicationModule(
        displayName = "Persistence",
        allowedDependencies = {"tournament", "tournament::port", "game::betting", "game::hand", "web::port"})
package com.agenttavern.persistence;
