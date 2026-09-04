package com.agenttavern.agents;

import java.util.List;

public final class AgentRoster {
    private static final List<AgentPersona> PERSONAS = List.of(
            new AgentPersona("vesper", "阿绯", "赤狐掌柜", "临江茶馆的掌柜，笑着听话，也笑着套话", 72, 88, 42, 65),
            new AgentPersona("hogarth", "豪哥", "野猪工头", "北边矿场出来的爽快人，喜欢拿筹码说话", 94, 34, 25, 78),
            new AgentPersona("mirelle", "沈听澜", "灰猫账房", "江南票号的旧账房，一眼能看出账对不对", 55, 60, 82, 36),
            new AgentPersona("bruno", "杜叔", "斗牛犬镖师", "退下来的老镖师，不抢风头，只守要紧处", 68, 28, 76, 52),
            new AgentPersona("bunji", "小满", "垂耳兔药师", "岭南药铺的小学徒，最能坐得住冷板凳", 26, 22, 96, 18),
            new AgentPersona("nyx", "墨羽", "渡鸦说书人", "茶楼里说过百家故事，也记得每次欲言又止", 48, 74, 88, 44),
            new AgentPersona("ragnar", "熊镇山", "棕熊商队主", "走过北地长路，认准方向就不会轻易回头", 86, 40, 38, 70),
            new AgentPersona("pip", "阿拾", "浣熊跑堂", "码头和茶馆都混得熟，手快，眼也快", 63, 92, 50, 84));

    private AgentRoster() {}

    public static List<AgentPersona> all() {
        return PERSONAS;
    }

    public static AgentPersona require(String key) {
        return PERSONAS.stream().filter(persona -> persona.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown agent persona: " + key));
    }
}
