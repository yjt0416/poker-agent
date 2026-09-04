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
        int conversationSignal = observation.recentMessages().stream()
                .mapToInt(message -> message.text().hashCode())
                .reduce(0, (left, right) -> left * 31 + right);
        int pressure = Math.floorMod(observation.self().hashCode() + observation.board().hashCode()
                + observation.persona().aggression()
                + conversationSignal * observation.persona().provocationSensitivity() / 100, 100);
        PlayerAction action;
        String summary;
        if (legal.types().contains(ActionType.RAISE) && pressure < observation.persona().aggression() / 3) {
            long min = legal.minRaiseTo().orElseThrow();
            long room = legal.maxRaiseTo() - min;
            action = PlayerAction.raiseTo(min + Math.min(room, Math.max(0, observation.pot() / 3)));
            summary = "筹码被往前一推，牌桌压力一下大了起来。";
        } else if (legal.types().contains(ActionType.CHECK)) {
            action = PlayerAction.check();
            summary = "暂时不把底池做大，准备再看一眼局势。";
        } else if (legal.types().contains(ActionType.CALL) && pressure < observation.persona().patience()) {
            action = PlayerAction.call();
            summary = "这个价钱还在接受范围内，选择继续跟着看。";
        } else {
            action = PlayerAction.fold();
            summary = "这次代价不划算，没有必要硬撑。";
        }
        return new AgentDecision(action, tableTalk(observation.persona().key(), action.type()),
                emotion(action.type()), summary, List.of());
    }

    private static String tableTalk(String persona, ActionType action) {
        return switch (persona) {
            case "vesper" -> switch (action) {
                case RAISE, ALL_IN -> "光听你说可不够。来，再添点筹码。";
                case CALL -> "行，这个故事我跟着听一段。";
                case CHECK -> "我先过，你慢慢演。";
                case FOLD -> "这回让你，账先记着。";
            };
            case "hogarth" -> switch (action) {
                case RAISE, ALL_IN -> "别绕弯子，咱们拿筹码说话。";
                case CALL -> "就这点？我跟。";
                case CHECK -> "不着急，你先来。";
                case FOLD -> "成，这把我不跟你较劲。";
            };
            case "mirelle" -> switch (action) {
                case RAISE, ALL_IN -> "账算清了，该你给个说法。";
                case CALL -> "价钱合适，我再看一张。";
                case CHECK -> "先记一笔，不急着结账。";
                case FOLD -> "这笔账不划算，到此为止。";
            };
            case "bruno" -> switch (action) {
                case RAISE, ALL_IN -> "路既然封了，那就硬闯一回。";
                case CALL -> "我还坐得住，跟上。";
                case CHECK -> "先稳一手。";
                case FOLD -> "该收手就收手，不丢人。";
            };
            case "bunji" -> switch (action) {
                case RAISE, ALL_IN -> "我等得够久了，这次得加。";
                case CALL -> "嗯……这个价还能接受。";
                case CHECK -> "先看看，不抢这一口气。";
                case FOLD -> "药可以慢熬，牌不能硬追。";
            };
            case "nyx" -> switch (action) {
                case RAISE, ALL_IN -> "这一折该起风了。";
                case CALL -> "故事没讲完，我跟着听。";
                case CHECK -> "留个空白，才好看下文。";
                case FOLD -> "这一页翻过去吧。";
            };
            case "ragnar" -> switch (action) {
                case RAISE, ALL_IN -> "路看准了，就往前走。加。";
                case CALL -> "这点风雪拦不住我。";
                case CHECK -> "先扎营，看看风向。";
                case FOLD -> "方向不对，回头不算输。";
            };
            default -> switch (action) {
                case RAISE, ALL_IN -> "机会来了，我可不客气。";
                case CALL -> "有点意思，我跟。";
                case CHECK -> "你先忙，我看着。";
                case FOLD -> "得，这趟没捡着便宜。";
            };
        };
    }

    private static AgentEmotion emotion(ActionType action) {
        return switch (action) {
            case RAISE, ALL_IN -> AgentEmotion.CONFIDENT;
            case CALL -> AgentEmotion.SUSPICIOUS;
            case CHECK -> AgentEmotion.CALM;
            case FOLD -> AgentEmotion.THINKING;
        };
    }
}
