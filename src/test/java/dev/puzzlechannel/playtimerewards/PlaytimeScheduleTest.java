package dev.puzzlechannel.playtimerewards;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class PlaytimeScheduleTest {
    @Test
    void countdownUsesConfiguredTimezone() {
        ZoneId utc = ZoneId.of("UTC");
        assertEquals(3600, PlaytimeSchedule.secondsUntilReset(Instant.parse("2026-01-01T23:00:00Z"), utc));
        assertEquals(1, PlaytimeSchedule.secondsUntilReset(Instant.parse("2026-01-01T23:59:59Z"), utc));
    }

    @Test
    void countdownHandlesDaylightSavingDays() {
        ZoneId stockholm = ZoneId.of("Europe/Stockholm");
        assertEquals(23 * 3600, PlaytimeSchedule.secondsUntilReset(
                Instant.parse("2026-03-28T23:00:00Z"), stockholm));
    }

    @Test
    void thresholdsMustBeAscendingAndWithinBounds() {
        assertTrue(PlaytimeSchedule.validThresholds(new int[]{1800, 3600, 7200, 14400}));
        assertFalse(PlaytimeSchedule.validThresholds(new int[]{1800, 3600, 3600, 14400}));
        assertFalse(PlaytimeSchedule.validThresholds(new int[]{0, 3600, 7200, 14400}));
        assertFalse(PlaytimeSchedule.validThresholds(new int[]{1800, 3600, 7200}));
    }

    @Test
    void durationFormattingOmitsZeroUnitsAndZeroPadding() {
        assertEquals("4ч", PlaytimeSchedule.formatDuration(14400));
        assertEquals("1м 5с", PlaytimeSchedule.formatDuration(65));
        assertEquals("1ч 25м 15с", PlaytimeSchedule.formatDuration(5115));
        assertEquals("1ч 5с", PlaytimeSchedule.formatDuration(3605));
        assertEquals("0с", PlaytimeSchedule.formatDuration(0));
        assertEquals("0с", PlaytimeSchedule.formatDuration(-12));
    }
}
