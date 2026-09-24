package com.agenttavern.agents;

import com.agenttavern.game.betting.ActionType;
import java.util.*;

/** Private, bounded memory for one agent within one tournament. Notes are untrusted model output. */
public record AgentMemory(Map<Integer, OpponentRead> opponents, List<String> notes,
                          List<String> recentUtterances, AgentEmotion emotion, int momentum, long decisions) {
    public AgentMemory {
        opponents = Map.copyOf(opponents);
        notes = bounded(notes, 12, 240);
        recentUtterances = bounded(recentUtterances, 6, 120);
        Objects.requireNonNull(emotion);
        if (opponents.size()>5 || opponents.keySet().stream().anyMatch(s->s<0||s>5)
                || momentum < -3 || momentum > 3 || decisions < 0) throw new IllegalArgumentException("invalid memory");
    }
    public static AgentMemory empty() { return new AgentMemory(Map.of(),List.of(),List.of(),AgentEmotion.CALM,0,0); }

    public AgentMemory observe(int seat, ActionType action) {
        var reads = new LinkedHashMap<>(opponents);
        var before = reads.getOrDefault(seat, new OpponentRead(0,0,0,0));
        reads.put(seat, new OpponentRead(inc(before.actions()),
                before.raises() + ((action==ActionType.RAISE||action==ActionType.ALL_IN)&&before.raises()<1_000_000?1:0),
                before.folds() + (action==ActionType.FOLD&&before.folds()<1_000_000?1:0), before.messages()));
        return new AgentMemory(reads,notes,recentUtterances,emotion,momentum,decisions);
    }
    public AgentMemory hear(int seat) {
        var reads = new LinkedHashMap<>(opponents);
        var before = reads.getOrDefault(seat,new OpponentRead(0,0,0,0));
        reads.put(seat,new OpponentRead(before.actions(),before.raises(),before.folds(),inc(before.messages())));
        return new AgentMemory(reads,notes,recentUtterances,emotion,momentum,decisions);
    }
    public AgentMemory remember(AgentDecision decision, String utterance) {
        var updated = new ArrayList<>(notes); updated.addAll(decision.memoryUpdates());
        var said = new ArrayList<>(recentUtterances); if (!utterance.isBlank()) said.add(utterance);
        AgentEmotion next = momentum > 0 ? AgentEmotion.DELIGHTED : momentum < 0 ? AgentEmotion.NERVOUS : decision.emotion();
        return new AgentMemory(opponents,updated,said,next,momentum,Math.min(Long.MAX_VALUE-1,decisions)+1);
    }
    public AgentMemory outcome(long netChips) {
        int next = Math.max(-3,Math.min(3,momentum+Long.signum(netChips)*2));
        return new AgentMemory(opponents,notes,recentUtterances,
                next>0?AgentEmotion.DELIGHTED:next<0?AgentEmotion.NERVOUS:AgentEmotion.CALM,next,decisions);
    }
    public AgentMemory nextHand() {
        int next = momentum - Integer.signum(momentum);
        return new AgentMemory(opponents,notes,recentUtterances,
                next==0?AgentEmotion.CALM:emotion,next,decisions);
    }
    /** Publicly observed aggression changes a local decision threshold by at most five points. */
    public int decisionBias() {
        int actions = opponents.values().stream().mapToInt(OpponentRead::actions).sum();
        int raises = opponents.values().stream().mapToInt(OpponentRead::raises).sum();
        if (actions<6) return 0;
        return Math.max(-5,Math.min(5,raises*20/actions-5));
    }
    private static int inc(int value) { return Math.min(1_000_000,value+1); }
    private static List<String> bounded(List<String> input,int count,int length) {
        var result = new ArrayList<String>();
        for (String value:input) {
            String clean = value.replaceAll("[\\p{Cc}\\p{Cf}]"," ").strip();
            if (clean.isBlank()) continue;
            clean = clean.substring(0,clean.offsetByCodePoints(0,Math.min(length,clean.codePointCount(0,clean.length()))));
            result.remove(clean); result.add(clean);
        }
        return List.copyOf(result.subList(Math.max(0,result.size()-count),result.size()));
    }
    public record OpponentRead(int actions,int raises,int folds,int messages) {
        public OpponentRead {
            if(actions<0||actions>1_000_000||raises<0||raises>actions||folds<0||folds>actions||messages<0||messages>1_000_000)
                throw new IllegalArgumentException("invalid opponent statistics");
        }
    }
}
