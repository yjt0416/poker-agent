package com.agenttavern.agents;

import com.agenttavern.game.betting.ActionType;
import com.agenttavern.game.betting.LegalActions;
import com.agenttavern.game.betting.PlayerAction;

/** Validates provider output and supplies the never-blocking CHECK/FOLD fallback. */
public final class AgentDecisionPolicy {
    private AgentDecisionPolicy() {}

    public static AgentDecision validateOrFallback(AgentObservation observation, AgentDecision candidate) {
        if (candidate != null && isLegal(observation.legalActions(), candidate.action())) return candidate;
        return fallback(observation.legalActions(), "Agent 响应不可用，已执行安全动作。");
    }

    public static AgentDecision fallback(LegalActions legal, String summary) {
        PlayerAction action = legal.types().contains(ActionType.CHECK) ? PlayerAction.check() : PlayerAction.fold();
        return new AgentDecision(action, "", AgentEmotion.CALM, summary, java.util.List.of());
    }

    public static boolean isLegal(LegalActions legal, PlayerAction action) {
        if (action == null || !legal.types().contains(action.type())) return false;
        return switch (action.type()) {
            case FOLD, CHECK, CALL -> action.amount() == 0;
            case RAISE -> legal.minRaiseTo().isPresent()
                    && action.amount() >= legal.minRaiseTo().getAsLong()
                    && action.amount() <= legal.maxRaiseTo();
            case ALL_IN -> action.amount() == legal.maxRaiseTo();
        };
    }
}
