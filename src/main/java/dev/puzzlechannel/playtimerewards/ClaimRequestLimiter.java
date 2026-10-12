package dev.puzzlechannel.playtimerewards;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe per-player rate limiter for packets received from the network thread.
 * The check happens before scheduling work on the server thread, keeping packet spam
 * from filling the server task queue.
 */
public final class ClaimRequestLimiter {
    private final long intervalNanos;
    private final ConcurrentMap<UUID, Long> lastAcceptedNanos = new ConcurrentHashMap<>();

    public ClaimRequestLimiter(long intervalNanos) {
        if (intervalNanos <= 0) throw new IllegalArgumentException("intervalNanos must be positive");
        this.intervalNanos = intervalNanos;
    }

    public boolean tryAcquire(UUID playerId, long nowNanos) {
        Objects.requireNonNull(playerId, "playerId");
        AtomicBoolean accepted = new AtomicBoolean(false);
        lastAcceptedNanos.compute(playerId, (ignored, previous) -> {
            if (previous == null || nowNanos - previous >= intervalNanos) {
                accepted.set(true);
                return nowNanos;
            }
            return previous;
        });
        return accepted.get();
    }

    public void clear(UUID playerId) {
        if (playerId != null) lastAcceptedNanos.remove(playerId);
    }
}
