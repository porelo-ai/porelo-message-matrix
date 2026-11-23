package org.fuin.porelo.message.matrix;

import java.time.Duration;
import org.fuin.porelo.wire.LimitFactors;

/**
 * The four factors this run's time limits follow, read once from {@code --limits} before the verb
 * runs. Held for the whole process, since one process runs one verb and every limit of it follows
 * the same factors.
 */
final class Limits {

    private static volatile LimitFactors factors = LimitFactors.DEFAULT;

    private Limits() {}

    /** The factors in force: those {@code --limits} named, or all four at 1.0. */
    static LimitFactors factors() {
        return factors;
    }

    /** Puts {@code given} in force for the rest of the run. */
    static void use(final LimitFactors given) {
        factors = given;
    }

    /** A limit on an answer from outside: the homeserver, a client, another Porelo program. */
    static Duration services(final Duration limit) {
        return factors.services(limit);
    }

    /** A limit on a program or container starting, stopping or running a command. */
    static Duration machines(final Duration limit) {
        return factors.machines(limit);
    }

    /** How long until something is looked at again. */
    static Duration polling(final Duration interval) {
        return factors.polling(interval);
    }

    /** How often a step is tried, at least once. */
    static int tries(final int tries) {
        return factors.tries(tries);
    }
}
