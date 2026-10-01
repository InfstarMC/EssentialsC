package cn.infstar.essentialsC.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class SeenCommandTest {

    @Test
    void convertsPlayTicksToDaysHoursAndMinutes() {
        int ticks = (2 * 24 * 60 + 3 * 60 + 4) * 60 * 20;

        assertArrayEquals(new long[]{2, 3, 4}, SeenCommand.durationParts(ticks));
        assertArrayEquals(new long[]{0, 0, 0}, SeenCommand.durationParts(-1));
    }
}
