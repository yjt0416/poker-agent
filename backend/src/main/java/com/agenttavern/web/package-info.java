@org.springframework.modulith.ApplicationModule(
        displayName = "Web Play",
        allowedDependencies = {
                "agents",
                "tablechat",
                "tournament",
                "tournament::application",
                "tournament::port",
                "game::betting",
                "game::card",
                "game::hand"
        })
package com.agenttavern.web;
