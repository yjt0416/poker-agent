package com.agenttavern.agents;

import com.agenttavern.game.betting.ActionType;
import com.agenttavern.game.betting.LegalActions;
import com.agenttavern.game.betting.PlayerAction;
import java.util.List;

/** Deterministic offline opponent used when no external LLM is configured. */
public final class LocalAgentDecisionProvider implements AgentDecisionProvider {
    @Override
    public AgentDecision decide(AgentObservation observation) {
        LegalActions legal = observation.legalActions();
        int pressure = Math.floorMod(observation.self().hashCode() + observation.board().hashCode()
                + observation.persona().aggression(), 100);
        PlayerAction action;
        String summary;
        if (legal.types().contains(ActionType.RAISE) && pressure < observation.persona().aggression() / 3) {
            long min = legal.minRaiseTo().orElseThrow();
            long room = legal.maxRaiseTo() - min;
            action = PlayerAction.raiseTo(min + Math.min(room, Math.max(0, observation.pot() / 3)));
            summary = "利用位置和筹码压力做出加注。";
        } else if (legal.types().contains(ActionType.CHECK)) {
            action = PlayerAction.check();
            summary = "控制底池并继续观察。";
        } else if (legal.types().contains(ActionType.CALL) && pressure < observation.persona().patience()) {
            action = PlayerAction.call();
            summary = "当前价格可以接受，选择跟注。";
        } else {
            action = PlayerAction.fold();
            summary = "风险超过角色当前承受范围。";
        }
        return new AgentDecision(action, "", AgentEmotion.THINKING, summary, List.of());
    }
}
