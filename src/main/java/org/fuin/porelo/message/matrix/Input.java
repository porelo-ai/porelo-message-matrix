package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What a verb gets from Porelo: its environment and its settings. A Porelo that declares the
 * secrets on the input hands one object on stdin, {@code {"settings": {...}, "secrets": {...}}}, so
 * no token is ever in the environment, which the kernel shows in {@code /proc/<pid>/environ} from
 * the moment of {@code exec}, before the transport can make itself unreadable. A Porelo of the old
 * way hands the bare settings on stdin and the secrets as the environment, and that passes as it
 * came.
 *
 * @param env the environment the verb reads, the input's secrets laid over it
 * @param settings the project's settings as JSON, as the lifecycle verbs read them
 */
record Input(Map<String, String> env, InputStream settings) {

    /** Far above any settings and secrets Porelo hands, and bounded, so no stream fills memory. */
    static final int LIMIT = 1 << 20;

    /** Every key the transport prints among its secrets, and so every key it reads from them. */
    private static final String PREFIX = "PORELO_MATRIX_";

    private static final Set<String> KEYS = Set.of("settings", "secrets");

    private static final ObjectReader READER =
            MatrixClient.JSON
                    .reader()
                    .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * The input read to its end and told apart. An object with {@code settings} or {@code secrets}
     * is the new way, and with any other key beside them refused; with {@code secrets} in it, no
     * {@code PORELO_MATRIX_} key of the environment is read, not even one the input lacks, so a
     * stale token is never used beside the input's. Anything else is the old way's, the bare
     * settings or nothing, and passes as it came: a verb that takes no settings never read its
     * stdin there.
     */
    static Input resolve(final InputStream in, final Map<String, String> env) throws Failure {
        final byte[] bytes = read(in);
        final JsonNode node = parse(bytes);
        if (node == null) {
            return new Input(env, new ByteArrayInputStream(bytes));
        }
        final JsonNode settings = node.path("settings");
        if (!settings.isMissingNode() && !settings.isObject()) {
            throw new Failure(Exit.CONFIG, "the settings on stdin are not a JSON object");
        }
        final byte[] text =
                settings.isMissingNode()
                        ? "{}".getBytes(StandardCharsets.UTF_8)
                        : settings.toString().getBytes(StandardCharsets.UTF_8);
        final JsonNode secrets = node.path("secrets");
        if (secrets.isMissingNode()) {
            return new Input(env, new ByteArrayInputStream(text));
        }
        return new Input(secrets(secrets, env), new ByteArrayInputStream(text));
    }

    private static byte[] read(final InputStream in) throws Failure {
        final byte[] bytes;
        try {
            bytes = in.readNBytes(LIMIT + 1);
        } catch (final IOException ex) {
            throw new Failure(Exit.CONFIG, "cannot read stdin: " + ex.getMessage(), ex);
        }
        if (bytes.length > LIMIT) {
            throw new Failure(Exit.CONFIG, "stdin holds more than " + LIMIT + " bytes");
        }
        return bytes;
    }

    /** The new way's object, or null for the old way, whose bytes the verb reads as they came. */
    private static @Nullable JsonNode parse(final byte[] bytes) throws Failure {
        if (bytes.length == 0) {
            return null;
        }
        final JsonNode node;
        try {
            node = READER.readTree(bytes);
        } catch (final IOException ex) {
            if (!mayHoldSecrets(bytes)) {
                // Left to the settings' own reading, which says what is wrong with them.
                return null;
            }
            // Where, never what: the parser's message quotes the input, and it may quote a secret.
            throw new Failure(
                    Exit.CONFIG, "stdin is not the JSON object Porelo hands" + where(ex), ex);
        }
        if (node == null || !node.isObject() || !hasAny(node)) {
            return null;
        }
        for (final Map.Entry<String, JsonNode> property : node.properties()) {
            if (!KEYS.contains(property.getKey())) {
                throw new Failure(
                        Exit.CONFIG,
                        "stdin holds '"
                                + property.getKey()
                                + "' beside the settings or the secrets, which Porelo never hands");
            }
        }
        return node;
    }

    private static boolean hasAny(final JsonNode node) {
        for (final String key : KEYS) {
            if (node.has(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Input the parser refused that may be the new way's: it names the settings or the secrets,
     * neither of which is a setting, so it is never handed to the settings' reading, which reads
     * leniently and whose message would quote a secret.
     */
    private static boolean mayHoldSecrets(final byte[] bytes) {
        final String text = new String(bytes, StandardCharsets.UTF_8);
        return text.contains("\"secrets\"") || text.contains("\"settings\"");
    }

    private static String where(final IOException ex) {
        if (ex instanceof JsonProcessingException json && json.getLocation() != null) {
            return " (line "
                    + json.getLocation().getLineNr()
                    + ", column "
                    + json.getLocation().getColumnNr()
                    + ")";
        }
        return "";
    }

    private static Map<String, String> secrets(
            final JsonNode secrets, final Map<String, String> env) throws Failure {
        if (!secrets.isObject()) {
            throw new Failure(Exit.CONFIG, "the secrets on stdin are not a JSON object");
        }
        final Map<String, String> resolved = new LinkedHashMap<>();
        env.forEach(
                (key, value) -> {
                    if (!key.startsWith(PREFIX)) {
                        resolved.put(key, value);
                    }
                });
        for (final Map.Entry<String, JsonNode> entry : secrets.properties()) {
            final String key = entry.getKey();
            // Named, never the value: a refusal goes to Porelo's log.
            if (!key.startsWith(PREFIX)) {
                throw new Failure(
                        Exit.CONFIG,
                        "the secret '" + key + "' on stdin is none of this transport's");
            }
            if (!entry.getValue().isTextual()) {
                throw new Failure(Exit.CONFIG, "the secret " + key + " on stdin is not a string");
            }
            resolved.put(key, entry.getValue().textValue());
        }
        return Map.copyOf(resolved);
    }
}
