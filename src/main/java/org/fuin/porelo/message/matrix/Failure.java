package org.fuin.porelo.message.matrix;

/**
 * Stops one invocation with an exit code and the reason a person reads on stderr. The reason never
 * carries a credential.
 */
class Failure extends Exception {

    private static final long serialVersionUID = 1L;

    private final int exitCode;

    Failure(final int exitCode, final String reason) {
        super(reason);
        this.exitCode = exitCode;
    }

    Failure(final int exitCode, final String reason, final Throwable cause) {
        super(reason, cause);
        this.exitCode = exitCode;
    }

    int exitCode() {
        return exitCode;
    }
}
