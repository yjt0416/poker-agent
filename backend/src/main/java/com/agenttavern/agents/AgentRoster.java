package com.agenttavern.agents;

import java.util.List;

public final class AgentRoster {
    private static final List<AgentPersona> PERSONAS = List.of(
            new AgentPersona("vesper", "维斯珀", "赤狐", "笑着偷走你的底池", 72, 88, 42, 65),
            new AgentPersona("hogarth", "霍加斯", "野猪", "用筹码把问题解决", 94, 34, 25, 78),
            new AgentPersona("mirelle", "米蕾尔", "灰猫", "每个破绽都有价格", 55, 60, 82, 36),
            new AgentPersona("bruno", "布鲁诺", "斗牛犬", "守住好牌，咬住坏人", 68, 28, 76, 52),
            new AgentPersona("bunji", "邦吉", "垂耳兔", "安静等到胜率开口", 26, 22, 96, 18),
            new AgentPersona("nyx", "妮克丝", "渡鸦", "牌桌会记住每一次迟疑", 48, 74, 88, 44),
            new AgentPersona("ragnar", "拉格纳", "棕熊", "我更相信压力而非运气", 86, 40, 38, 70),
            new AgentPersona("pip", "皮普", "浣熊", "没人知道下一枚筹码去哪", 63, 92, 50, 84));

    private AgentRoster() {}

    public static List<AgentPersona> all() {
        return PERSONAS;
    }

    public static AgentPersona require(String key) {
        return PERSONAS.stream().filter(persona -> persona.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown agent persona: " + key));
    }
}
