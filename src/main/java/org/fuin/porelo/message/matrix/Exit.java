package org.fuin.porelo.message.matrix;

/**
 * The exit codes the transport answers with. Porelo's contract gives meaning to two of them - 0
 * handed over, 75 try again later - and refuses a message back to its sender on every other, so
 * each refusal gets its own code for the person reading why.
 */
final class Exit {

    /** Handed over. */
    static final int OK = 0;

    /** The command line was wrong - nothing was attempted. */
    static final int USAGE = 64;

    /** The message itself cannot be carried: not UTF-8, or too large for one event. */
    static final int DATA = 65;

    /** The homeserver answered something this transport does not understand. */
    static final int PROTOCOL = 76;

    /**
     * Temporary: the homeserver is unreachable, overloaded or rate-limiting. Porelo keeps it and
     * retries.
     */
    static final int TEMPORARY = 75;

    /**
     * The homeserver refused: the token, or the account is not in the room. Retrying cannot help.
     */
    static final int NOT_PERMITTED = 77;

    /** The transport's own configuration is missing or unusable. */
    static final int CONFIG = 78;

    private Exit() {}
}
