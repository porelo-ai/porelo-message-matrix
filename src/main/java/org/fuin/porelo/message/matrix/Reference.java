package org.fuin.porelo.message.matrix;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A message's reference as Porelo hands it back: the event id {@code send} printed, or the name
 * {@code poll} gave the message's file, less {@code .json}. That name cannot be the event id: the
 * filter reads a message's file name with it, and an event id is 43 characters of base64url -
 * encoded data, which may not pass. So the name is the event's time and a UUID made from its id,
 * the shape the other transports' names have, and {@code poll} keeps what each name stands for in
 * the transport's state, where {@code read} looks it up.
 */
final class Reference {

    private static final Pattern EVENT_ID = Pattern.compile("\\$[A-Za-z0-9_-]{1,255}");

    private static final Pattern NAME =
            Pattern.compile("[0-9]{8}T[0-9]{9}--matrix-(person-)?[0-9a-f-]{36}");

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC);

    private Reference() {}

    /**
     * The name for an event: the same every time it is delivered, so a second delivery replaces the
     * first.
     */
    static String of(final String eventId, final long originServerTs) {
        return TIME.format(Instant.ofEpochMilli(originServerTs))
                + "--matrix-"
                + UUID.nameUUIDFromBytes(eventId.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The name for a person's message: as {@link #of}, and saying whose it is, since it carries no
     * signature.
     */
    static String ofPerson(final String eventId, final long originServerTs) {
        return of(eventId, originServerTs).replace("--matrix-", "--matrix-person-");
    }

    static void remember(final Path stateDir, final String reference, final String eventId)
            throws Failure {
        final Path dir = stateDir.resolve("refs");
        try {
            Files.createDirectories(dir);
            final Path temporary = dir.resolve("." + reference + ".part");
            Files.writeString(temporary, eventId, StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    dir.resolve(reference),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.TEMPORARY,
                    "cannot keep what " + reference + " stands for: " + ex.getMessage(),
                    ex);
        }
    }

    /**
     * The event a reference stands for: an event id as it is, a file's name as poll recorded it.
     */
    static String eventId(final String reference, final Map<String, String> env) throws Failure {
        if (EVENT_ID.matcher(reference).matches()) {
            return reference;
        }
        if (!NAME.matcher(reference).matches()) {
            throw new Failure(
                    Exit.USAGE,
                    "the reference must be an event id or the name poll gave a message, not '"
                            + reference
                            + "'");
        }
        final Path file = Poll.stateDirectory(env).resolve("refs").resolve(reference);
        try {
            return Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (final IOException ex) {
            // Not delivered here, or its record gone: which event is meant cannot be told, so none
            // is guessed.
            throw new Failure(
                    Exit.NOT_PERMITTED,
                    "no message named " + reference + " was delivered on this account");
        }
    }
}
