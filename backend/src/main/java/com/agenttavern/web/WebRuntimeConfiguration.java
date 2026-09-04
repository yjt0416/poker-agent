package com.agenttavern.web;

import com.agenttavern.agents.AgentDecisionProvider;
import com.agenttavern.agents.LocalAgentDecisionProvider;
import com.agenttavern.game.card.Deck;
import com.agenttavern.tablechat.TableChatService;
import com.agenttavern.tournament.application.TournamentCommandService;
import com.agenttavern.tournament.port.TournamentStore;
import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
class WebRuntimeConfiguration {
    @Bean
    @Profile("local")
    TournamentStore runtimeTournamentStore() {
        return new RuntimeTournamentStore();
    }

    @Bean
    @ConditionalOnMissingBean
    Clock agentTavernClock() {
        return Clock.systemUTC();
    }

    @Bean
    Supplier<Deck> shuffledDeckSupplier() {
        return () -> Deck.standard().shuffled(ThreadLocalRandom.current());
    }

    @Bean
    TournamentCommandService tournamentCommandService(
            TournamentStore store, Clock clock, Supplier<Deck> shuffledDeckSupplier) {
        return new TournamentCommandService(store, clock, shuffledDeckSupplier);
    }

    @Bean
    @ConditionalOnProperty(name = "agent-tavern.agent-provider", havingValue = "local", matchIfMissing = true)
    AgentDecisionProvider localAgentDecisionProvider() {
        return new LocalAgentDecisionProvider();
    }

    @Bean
    TableChatService tableChatService(Clock clock) {
        return new TableChatService(clock);
    }
}
