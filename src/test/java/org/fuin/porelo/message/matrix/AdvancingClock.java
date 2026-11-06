package org.fuin.porelo.message.matrix;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A thread-safe clock advanced by a test instead of real-time waits. */
final class AdvancingClock extends Clock {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);

    void advance(final Duration duration) {
        now.updateAndGet(instant -> instant.plus(duration));
    }

    long nanoTime() {
        return Duration.between(Instant.EPOCH, instant()).toNanos();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
