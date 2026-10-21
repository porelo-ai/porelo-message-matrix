package org.fuin.porelo.message.matrix;

import java.util.Map;

/**
 * What the transport asks of the account's own homeserver: whether it is installed and running, to
 * start and stop it, to make a backup, and one run of its program on its database while it is
 * stopped. The unit of the package answers through {@code systemctl --user} and podman; a test
 * answers with the homeserver's binary as a process.
 */
interface Unit {

    /** Whether the homeserver is installed for this account. */
    boolean installed() throws Failure;

    /** Whether it runs now. */
    boolean active() throws Failure;

    /** Started, and enabled so it comes back after a boot; it may still be starting on return. */
    void start() throws Failure;

    /** Stopped; its database is then free for a run of its own. */
    void stop() throws Failure;

    /** Whether it has a database, made by a start or a restore. */
    boolean hasDatabase() throws Failure;

    /** Its database removed: only what a restore that failed made, which found none there. */
    void removeDatabase() throws Failure;

    /** Has the running homeserver make an online backup into its backup directory. */
    void signalBackup() throws Failure;

    /**
     * One run of the homeserver's program on its database and backup directory, with registration
     * off and no port published, while the unit is stopped. The arguments end it; the values in
     * {@code secrets} reach it through the environment only, and its output, which may repeat them,
     * is discarded. Answers its exit code.
     */
    int runOnce(java.util.List<String> args, Map<String, String> secrets) throws Failure;
}
