package dev.puzzlechannel.playtimerewards;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

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
    @Test
    void claimPacketLimiterRejectsBurstsAndSeparatesPlayers() {
        ClaimRequestLimiter limiter = new ClaimRequestLimiter(500);
        UUID firstPlayer = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondPlayer = UUID.fromString("00000000-0000-0000-0000-000000000002");

        assertTrue(limiter.tryAcquire(firstPlayer, 1_000));
        assertFalse(limiter.tryAcquire(firstPlayer, 1_100));
        assertFalse(limiter.tryAcquire(firstPlayer, 1_499));
        assertTrue(limiter.tryAcquire(firstPlayer, 1_500));

        assertTrue(limiter.tryAcquire(secondPlayer, 1_100));
        limiter.clear(firstPlayer);
        assertTrue(limiter.tryAcquire(firstPlayer, 1_101));
    }

}
