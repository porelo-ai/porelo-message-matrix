package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** The people a room line addresses, without any transport or state. */
record MeantFor(Set<String> users, boolean room) {

    /** A task as the room shows it, its agent and its short name where that differs, as a word. */
    private static final Pattern DISPLAY =
            Pattern.compile(
                    "(?<![\\p{L}\\p{N}_])Agent [\\p{L}\\p{N}_]+(?: \\([\\p{L}\\p{N}_.-]+\\))?");

    /** The mentions for a post, excluding its writer. */
    static MeantFor post(
            final String body, final Map<String, String> members, final String writer) {
        final MeantFor head = head(body, members);
        final Set<String> users = new LinkedHashSet<>(head.users());
        users.remove(writer);
        return new MeantFor(Set.copyOf(users), head.room());
    }

    /** Names in the first line's head, for the mentions sent with a post. */
    static MeantFor head(final String body, final Map<String, String> members) {
        final String head = headOf(body);
        if (head == null) {
            return new MeantFor(Set.of(), false);
        }
        final Set<String> named = displays(head);
        final Set<String> users = new LinkedHashSet<>();
        members.forEach(
                (id, name) -> {
                    if (named.contains(name)) {
                        users.add(id);
                    }
                });
        return new MeantFor(Set.copyOf(users), phrase(head.toLowerCase(Locale.ROOT), "all agents"));
    }

    /**
     * Whether the first line's head may name a member shown as {@code Agent <Word>} or {@code Agent
     * <Word> (<short>)}, decided without the members, so a line that cannot name one costs no
     * request for them.
     */
    static boolean mayNameAnAgent(final String body) {
        final String head = headOf(body);
        return head != null && !displays(head).isEmpty();
    }

    /** The first line up to its first colon, within the first 300 code points; or none. */
    private static @Nullable String headOf(final String body) {
        final String first = body.lines().findFirst().orElse("");
        final int colon = first.indexOf(':');
        if (colon < 0 || first.codePointCount(0, colon) >= 300) {
            return null;
        }
        return first.substring(0, colon);
    }

    /**
     * Every task the head names, each as a whole display, so {@code Agent Pi} is never read out of
     * {@code Agent Pi (fix)} nor the other way round.
     */
    private static Set<String> displays(final String head) {
        final Set<String> named = new LinkedHashSet<>();
        final Matcher m = DISPLAY.matcher(head);
        // Each search starts just past the last one's start, so "Agent Agent Pi" still names Pi.
        for (int from = 0; from < head.length() && m.find(from); from = m.start() + 1) {
            named.add(m.group());
        }
        return named;
    }

    /** A name or broadcast inside a longer word must not address another reader. */
    private static boolean phrase(final String head, final String phrase) {
        for (int from = head.indexOf(phrase); from >= 0; from = head.indexOf(phrase, from + 1)) {
            final int end = from + phrase.length();
            if ((from == 0 || !word(head.codePointBefore(from)))
                    && (end == head.length() || !word(head.codePointAt(end)))) {
                return true;
            }
        }
        return false;
    }

    /** Keeps phrase boundaries consistent with the letters, numbers and underscore in names. */
    private static boolean word(final int point) {
        return Character.isLetterOrDigit(point)
                || point == '_'
                || Character.getType(point) == Character.LETTER_NUMBER
                || Character.getType(point) == Character.OTHER_NUMBER;
    }

    /** Whether another member's text addresses this account. */
    static boolean line(
            final JsonNode event, final String self, final Map<String, String> members) {
        if (self.equals(event.path("sender").asText())) {
            return false;
        }
        final JsonNode content = event.path("content");
        final MeantFor head = head(content.path("body").asText(""), members);
        final Set<String> users = new LinkedHashSet<>(head.users());
        final JsonNode mentions = content.path("m.mentions");
        for (final JsonNode user : mentions.path("user_ids")) {
            if (user.isTextual() && members.containsKey(user.asText())) {
                users.add(user.asText());
            }
        }
        users.remove(event.path("sender").asText());
        return head.room()
                || mentions.path("room").asBoolean(false)
                || users.isEmpty()
                || users.contains(self);
    }
}
