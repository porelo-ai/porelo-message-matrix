package org.fuin.porelo.message.matrix;

import java.util.List;
import java.util.Map;

/**
 * {@code check}: whether the transport could work now - its configuration is complete, the
 * homeserver answers, and it accepts the token - sending nothing. The contract knows two answers,
 * usable or not, so every reason it is not becomes the one exit code, with the reason on stderr.
 */
final class Check {

    static final int NOT_USABLE = 2;

    private Check() {}

    static void run(final List<String> args, final Map<String, String> env) throws Failure {
        if (!args.isEmpty()) {
            throw new Failure(NOT_USABLE, "usage: check");
        }
        try {
            final String userId =
                    new MatrixClient(Config.from(env))
                            .get("/_matrix/client/v3/account/whoami")
                            .path("user_id")
                            .asText("");
            if (userId.isEmpty()) {
                throw new Failure(
                        NOT_USABLE,
                        "the homeserver accepted the token but did not say whose it is");
            }
        } catch (final Failure failure) {
            throw new Failure(
                    NOT_USABLE,
                    failure.getMessage() == null ? "not usable" : failure.getMessage(),
                    failure);
        }
    }
}
