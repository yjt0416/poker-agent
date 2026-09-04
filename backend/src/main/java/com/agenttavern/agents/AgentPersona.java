package com.agenttavern.agents;

import static java.util.Objects.requireNonNull;

public record AgentPersona(
        String key,
        String name,
        String species,
        String tagline,
        int aggression,
        int bluffing,
        int patience,
        int provocationSensitivity) {

    public AgentPersona {
        requireText(key, "key");
        requireText(name, "name");
        requireText(species, "species");
        requireText(tagline, "tagline");
        requireScale(aggression, "aggression");
        requireScale(bluffing, "bluffing");
        requireScale(patience, "patience");
        requireScale(provocationSensitivity, "provocationSensitivity");
    }

    private static void requireText(String value, String field) {
        requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    private static void requireScale(int value, String field) {
        if (value < 0 || value > 100) throw new IllegalArgumentException(field + " must be between 0 and 100");
    }
}
