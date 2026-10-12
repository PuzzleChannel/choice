package dev.puzzlechannel.playtimerewards;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/** Pure time calculations shared by runtime code and unit tests. */
public final class PlaytimeSchedule {
    private PlaytimeSchedule() { }

    public static int secondsUntilReset(Instant now, ZoneId zone) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(zone, "zone");
        ZonedDateTime localNow = now.atZone(zone);
        LocalDate nextDate = localNow.toLocalDate().plusDays(1);
        Instant nextReset = nextDate.atStartOfDay(zone).toInstant();
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, Duration.between(now, nextReset).getSeconds()));
    }

    public static boolean validThresholds(int[] thresholds) {
        if (thresholds == null || thresholds.length != 4) return false;
        int previous = 0;
        for (int threshold : thresholds) {
            if (threshold < 60 || threshold > 86400 || threshold <= previous) return false;
            previous = threshold;
        }
        return true;
    }

    public static String formatDuration(int totalSeconds) {
        int safe = Math.max(0, totalSeconds);
        long hours = safe / 3600;
        long minutes = (safe % 3600) / 60;
        long seconds = safe % 60;

        StringBuilder result = new StringBuilder();
        if (hours > 0) result.append(hours).append("ч ");
        if (minutes > 0) result.append(minutes).append("м ");
        if (seconds > 0 || result.isEmpty()) result.append(seconds).append("с");
        return result.toString().trim();
    }
}
