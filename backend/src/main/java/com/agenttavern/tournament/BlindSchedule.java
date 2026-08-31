package com.agenttavern.tournament;

import static java.util.Objects.requireNonNull;

import com.agenttavern.game.hand.BlindLevel;
import java.util.List;

public final class BlindSchedule {

    private static final int HANDS_PER_LEVEL = 8;
    private static final BlindSchedule STANDARD = new BlindSchedule(List.of(
            new BlindLevel(50, 100),
            new BlindLevel(75, 150),
            new BlindLevel(100, 200),
            new BlindLevel(150, 300),
            new BlindLevel(200, 400),
            new BlindLevel(300, 600),
            new BlindLevel(400, 800),
            new BlindLevel(600, 1_200),
            new BlindLevel(800, 1_600),
            new BlindLevel(1_000, 2_000),
            new BlindLevel(1_500, 3_000),
            new BlindLevel(2_000, 4_000),
            new BlindLevel(3_000, 6_000),
            new BlindLevel(4_000, 8_000),
            new BlindLevel(6_000, 12_000)));

    private final List<BlindLevel> levels;

    private BlindSchedule(List<BlindLevel> levels) {
        requireNonNull(levels, "levels");
        if (levels.isEmpty()) {
            throw new IllegalArgumentException("blind schedule must contain at least one level");
        }
        this.levels = List.copyOf(levels);
    }

    public static BlindSchedule standard() {
        return STANDARD;
    }

    public BlindLevel forCompletedHands(long completedHands) {
        return forLevelIndex(levelIndexForCompletedHands(completedHands));
    }

    public int levelIndexForCompletedHands(long completedHands) {
        if (completedHands < 0) {
            throw new IllegalArgumentException("completed hands must be non-negative");
        }
        long uncappedIndex = completedHands / HANDS_PER_LEVEL;
        return (int) Math.min(uncappedIndex, levels.size() - 1L);
    }

    public BlindLevel forLevelIndex(int levelIndex) {
        if (levelIndex < 0 || levelIndex >= levels.size()) {
            throw new IllegalArgumentException("blind-level index is outside the schedule");
        }
        return levels.get(levelIndex);
    }

    public int levelCount() {
        return levels.size();
    }

    public List<BlindLevel> levels() {
        return levels;
    }
}
