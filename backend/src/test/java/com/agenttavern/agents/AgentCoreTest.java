package com.agenttavern.agents;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenttavern.game.betting.ActionType;
import com.agenttavern.game.betting.LegalActions;
import com.agenttavern.game.betting.PlayerAction;
import com.agenttavern.game.betting.PlayerId;
import com.agenttavern.game.betting.Street;
import com.agenttavern.game.card.Card;
import com.agenttavern.game.card.Rank;
import com.agenttavern.game.card.Suit;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentCoreTest {
    @Test
    void rosterContainsEightDistinctCompletePersonas() {
        assertThat(AgentRoster.all()).hasSize(8).extracting(AgentPersona::key).doesNotHaveDuplicates();
        assertThat(AgentRoster.all()).allSatisfy(persona -> {
            assertThat(persona.name()).isNotBlank();
            assertThat(persona.tagline()).isNotBlank();
        });
    }

    @Test
    void observationAndDecisionDefensivelyCopyPrivateInputs() {
        List<Card> hole = new ArrayList<>(List.of(card(Rank.ACE), card(Rank.KING)));
        AgentObservation observation = observation(hole, legal(ActionType.CHECK, ActionType.ALL_IN));
        hole.clear();
        assertThat(observation.holeCards()).hasSize(2);
        assertThatThrownByUnsupported(() -> observation.holeCards().clear());
    }

    @Test
    void invalidProviderRaiseFallsBackToCheckOrFold() {
        AgentDecision invalid = new AgentDecision(PlayerAction.raiseTo(99), "", AgentEmotion.CALM, "x", List.of());
        assertThat(AgentDecisionPolicy.validateOrFallback(
                observation(List.of(card(Rank.ACE), card(Rank.KING)), legal(ActionType.CHECK, ActionType.ALL_IN)), invalid)
                .action()).isEqualTo(PlayerAction.check());
        assertThat(AgentDecisionPolicy.validateOrFallback(
                observation(List.of(card(Rank.ACE), card(Rank.KING)), legal(ActionType.FOLD, ActionType.CALL)), invalid)
                .action()).isEqualTo(PlayerAction.fold());
    }

    @Test
    void offlineProviderIsDeterministicAndAlwaysLegal() {
        AgentObservation observation = observation(List.of(card(Rank.QUEEN), card(Rank.JACK)),
                new LegalActions(Set.of(ActionType.FOLD, ActionType.CALL, ActionType.RAISE, ActionType.ALL_IN),
                        50, OptionalLong.of(200), 1_000));
        LocalAgentDecisionProvider provider = new LocalAgentDecisionProvider();
        AgentDecision first = provider.decide(observation);
        assertThat(provider.decide(observation)).isEqualTo(first);
        assertThat(AgentDecisionPolicy.isLegal(observation.legalActions(), first.action())).isTrue();
    }

    private static AgentObservation observation(List<Card> hole, LegalActions legal) {
        PlayerId id = new PlayerId(new UUID(0, 7));
        return new AgentObservation(id, AgentRoster.require("vesper"), hole, List.of(), List.of(),
                Street.PREFLOP, 150, legal, List.of());
    }

    private static LegalActions legal(ActionType... types) {
        Set<ActionType> set = Set.of(types);
        long call = set.contains(ActionType.CALL) ? 50 : 0;
        long max = set.contains(ActionType.ALL_IN) ? 1_000 : Math.max(call, 0);
        return new LegalActions(set, call, OptionalLong.empty(), max);
    }

    private static Card card(Rank rank) { return new Card(Suit.SPADES, rank); }

    private static void assertThatThrownByUnsupported(Runnable action) {
        org.assertj.core.api.Assertions.assertThatThrownBy(action::run)
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
