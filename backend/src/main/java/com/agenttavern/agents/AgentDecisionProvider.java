package com.agenttavern.agents;

@FunctionalInterface
public interface AgentDecisionProvider {
    AgentDecision decide(AgentObservation observation);
}
