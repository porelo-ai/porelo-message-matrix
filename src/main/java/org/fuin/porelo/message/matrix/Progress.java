package org.fuin.porelo.message.matrix;

import java.io.PrintStream;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/** Reports a phase on stderr until its scope ends; phase changes and timer ticks are serialized. */
final class Progress implements AutoCloseable {

    static final Duration INTERVAL = Duration.ofSeconds(10);

    private final LongSupplier nanoTime;
    private final Duration interval;
    private final PrintStream err;
    private final List<String> secrets;
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(
                    task -> Thread.ofPlatform().daemon().name("matrix-progress").unstarted(task));

    private String phase = "";
    private Duration within = Duration.ZERO;
    private long began;
    private boolean bounded;
    private boolean timedOut;
    private Duration reported = Duration.ZERO;
    private boolean closed;
    private @Nullable ScheduledFuture<?> pending;

    Progress(final PrintStream err) {
        this(System::nanoTime, INTERVAL, err, List.of());
    }

    Progress(
            final LongSupplier nanoTime,
            final Duration interval,
            final PrintStream err,
            final List<String> secrets) {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("the progress interval must be positive");
        }
        this.nanoTime = nanoTime;
        this.interval = interval;
        this.err = err;
        this.secrets = List.copyOf(secrets);
        began = nanoTime.getAsLong();
    }

    synchronized void phase(final String name) {
        start(name, Duration.ZERO, false);
    }

    synchronized void phase(final String name, final Duration limit) {
        start(name, limit, true);
    }

    private void start(final String name, final Duration limit, final boolean hasLimit) {
        phase = name;
        // A limit is said in whole seconds, rounded up, so time left a moment after a deadline
        // was set is still said as the whole wait.
        within =
                limit.truncatedTo(ChronoUnit.SECONDS).equals(limit)
                        ? limit
                        : limit.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
        bounded = hasLimit;
        timedOut = false;
        began = nanoTime.getAsLong();
        print(Duration.ZERO);
        final ScheduledFuture<?> previous = pending;
        if (previous != null) {
            previous.cancel(false);
        }
        pending =
                timer.scheduleWithFixedDelay(
                        this::tick, interval.toNanos(), interval.toNanos(), TimeUnit.NANOSECONDS);
    }

    synchronized void tick() {
        final Duration elapsed = elapsed();
        if (!closed
                && !timedOut
                && !phase.isEmpty()
                && elapsed.dividedBy(interval) > reported.dividedBy(interval)) {
            print(elapsed);
        }
    }

    synchronized void timeout() {
        if (closed || timedOut) {
            return;
        }
        timedOut = true;
        // A wait that timed out has used its limit, even where that was rounded up to a second.
        final Duration elapsed = bounded ? within : elapsed();
        if (elapsed.toSeconds() != reported.toSeconds()) {
            print(elapsed);
        }
    }

    private Duration elapsed() {
        final Duration elapsed = Duration.ofNanos(nanoTime.getAsLong() - began);
        return bounded && elapsed.compareTo(within) > 0 ? within : elapsed;
    }

    private void print(final Duration elapsed) {
        final String line =
                phase
                        + " ("
                        + elapsed.toSeconds()
                        + " s"
                        + (bounded ? " of " + within.toSeconds() : "")
                        + ")";
        err.println(LocalHomeserver.progressLine(line, secrets));
        err.flush();
        reported = elapsed;
    }

    /** Stops ticks before the next phase or the command's final failure can be printed. */
    @Override
    public synchronized void close() {
        closed = true;
        timer.shutdownNow();
    }
}
