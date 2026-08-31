package com.agenttavern.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.agenttavern.game.hand.BlindLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BlindScheduleTest {

    @ParameterizedTest
    @CsvSource({"0,50,100", "7,50,100", "8,75,150", "112,6000,12000", "999,6000,12000"})
    void standardScheduleAdvancesEveryEightCompletedHands(
            long completedHands, long smallBlind, long bigBlind) {
        assertThat(BlindSchedule.standard().forCompletedHands(completedHands))
                .isEqualTo(new BlindLevel(smallBlind, bigBlind));
    }

    @Test
    void standardScheduleStaysAtTheFinalLevel() {
        BlindSchedule schedule = BlindSchedule.standard();

        assertThat(schedule.forCompletedHands(112)).isEqualTo(new BlindLevel(6_000, 12_000));
        assertThat(schedule.forCompletedHands(500)).isEqualTo(new BlindLevel(6_000, 12_000));
        assertThatIllegalArgumentException().isThrownBy(() -> schedule.forCompletedHands(-1));
    }
}
