package com.agenttavern.agents;

import com.agenttavern.game.betting.ActionType;
import com.agenttavern.game.betting.LegalActions;
import com.agenttavern.game.betting.PlayerAction;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import com.agenttavern.game.handvalue.HandEvaluator;

/** Deterministic offline opponent used when no external LLM is configured. */
public final class LocalAgentDecisionProvider implements AgentDecisionProvider {
    @Override
    public AgentDecision decide(AgentObservation observation) {
        LegalActions legal = observation.legalActions();
        int base = Math.floorMod(Objects.hash(observation.self(), observation.holeCards(),
                observation.board(), observation.pot(), legal.callAmount()), 100);
        int conversationSignal = Math.min(3, observation.recentMessages().size())
                * observation.persona().provocationSensitivity() / 100;
        int pressure = Math.max(0, Math.min(99, base + observation.memory().decisionBias() + conversationSignal));
        int strength = strength(observation);
        var self = observation.seats().stream().filter(s -> s.playerId().equals(observation.self())).findFirst();
        long stack = self.map(ObservedSeat::stack).orElse(legal.maxRaiseTo());
        long committed = self.map(ObservedSeat::committed).orElse(0L);
        long streetCommitted = Math.max(0, legal.maxRaiseTo() - stack);
        // A whole-hand budget prevents repeated minimum raises from silently becoming a shove.
        double fraction = strength >= 94 ? 1 : strength >= 85 ? .65 : strength >= 70 ? .35
                : strength >= 55 ? .18 : strength >= 40 ? .08 : .02;
        long budget = (long) ((stack + (double) committed) * fraction);
        long remaining = Math.min(stack, Math.max(0, budget - committed));
        double price = legal.callAmount() / Math.max(1.0, observation.pot() + (double) legal.callAmount());
        boolean affordable = legal.callAmount() <= remaining
                && (strength >= 85 || price <= strength / 200.0 + observation.persona().patience() / 1000.0);
        boolean valueRaise = strength >= 55 && pressure < observation.persona().aggression() / 2;
        boolean bluff = strength < 55 && legal.callAmount() == 0
                && pressure < observation.persona().bluffing() / 8;
        PlayerAction action;
        String summary;
        if (legal.types().contains(ActionType.RAISE) && affordable && (valueRaise || bluff)
                && legal.minRaiseTo().orElseThrow() - streetCommitted <= remaining) {
            long min = legal.minRaiseTo().orElseThrow();
            long ceiling = Math.min(legal.maxRaiseTo(), streetCommitted + remaining);
            // Target is total street contribution, not an increment on every re-raise.
            long extra = observation.pot() / 3;
            long target = Math.min(ceiling, Math.max(min, streetCommitted + legal.callAmount()
                    + Math.min(extra, remaining - legal.callAmount())));
            action = PlayerAction.raiseTo(target);
            summary = "筹码被往前一推，牌桌压力一下大了起来。";
        } else if (legal.types().contains(ActionType.CHECK)) {
            action = PlayerAction.check();
            summary = "暂时不把底池做大，准备再看一眼局势。";
        } else if (legal.types().contains(ActionType.CALL) && affordable) {
            action = PlayerAction.call();
            summary = "这个价钱还在接受范围内，选择继续跟着看。";
        } else {
            action = PlayerAction.fold();
            summary = "这次代价不划算，没有必要硬撑。";
        }
        String reaction = observation.memory().momentum()>0 ? "刚才那手不错。"
                : observation.memory().momentum()<0 ? "上一手算我失算。" : "";
        String talk = reaction + tableTalk(observation.persona().key(), action.type());
        if (observation.memory().recentUtterances().contains(talk)) {
            List<String> alternatives = switch (action.type()) {
                case FOLD -> List.of("茶还温着，这手先歇。", "不争这一口气，下一手再说。", "这回收手，我记住了。");
                case CHECK -> List.of("先听听桌上的动静。", "筹码先不动，看看再说。", "这一手，慢慢来。");
                case CALL -> List.of("再陪你走一段。", "我跟着，看看下文。", "行，继续看牌。");
                default -> List.of("这一轮，我想争一争。", "再添一点，该你考虑了。", "茶可以慢喝，机会不能错过。");
            };
            talk = alternatives.stream().map(s->reaction+s)
                    .filter(s->!observation.memory().recentUtterances().contains(s)).findFirst().orElse("");
        }
        return new AgentDecision(action, talk,
                emotion(action.type()), summary, List.of());
    }

    /** A conservative made-hand heuristic, not an equity estimate or knowledge of hidden cards. */
    private static int strength(AgentObservation o) {
        var first = o.holeCards().get(0);
        var second = o.holeCards().get(1);
        int high = Math.max(first.rank().strength(), second.rank().strength());
        int low = Math.min(first.rank().strength(), second.rank().strength());
        if (o.board().size() < 3) {
            if (high == low) return 50 + high * 3;
            return Math.min(80, high * 3 + low + (first.suit() == second.suit() ? 8 : 0)
                    + (high - low == 1 ? 5 : 0));
        }
        var cards = new ArrayList<>(o.board());
        cards.addAll(o.holeCards());
        var value = HandEvaluator.evaluate(cards);
        // A monster on the board belongs to everyone; don't pay a stack merely to play the board.
        if (o.board().size() == 5 && value.compareTo(HandEvaluator.evaluate(o.board())) == 0) return 40;
        return switch (value.category()) {
            case HIGH_CARD -> 20 + high;
            case ONE_PAIR -> o.holeCards().stream().anyMatch(c -> c.rank().strength() == value.tieBreakers().getFirst())
                    ? 48 + value.tieBreakers().getFirst() : 35;
            case TWO_PAIR -> 70;
            case THREE_OF_A_KIND -> 80;
            case STRAIGHT -> 88;
            case FLUSH -> 91;
            case FULL_HOUSE, FOUR_OF_A_KIND, STRAIGHT_FLUSH -> 96;
        };
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
