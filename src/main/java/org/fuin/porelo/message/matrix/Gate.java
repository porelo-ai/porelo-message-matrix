package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jdk.net.ExtendedSocketOptions;
import org.jspecify.annotations.Nullable;

/** The host's per-connection Matrix gate, with no caller-selected administrative operation. */
final class Gate {
    private static final String CLIENT = "/_matrix/client/";
    private static final String WHOAMI = CLIENT + "v3/account/whoami";
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    interface Backend {
        GateResponse exchange(String token, String method, String path, @Nullable JsonNode body)
                throws Failure, IOException;

        int forward(GateRequest request, OutputStream out) throws Failure, IOException;

        default GateResponse passwordLogin(final GateRequest request) throws Failure, IOException {
            return exchange("", request.method(), request.path(), body(request));
        }
    }

    private final String login;
    private final Map<String, String> env;
    private final Path group;
    private final Path admins;
    private final Backend backend;
    private final PrintStream err;
    private final GateLoginTokens tokens;
    private final Map<String, String> identities = new LinkedHashMap<>();

    Gate(
            final String login,
            final Map<String, String> env,
            final Path group,
            final Path admins,
            final Backend backend,
            final PrintStream err)
            throws Failure {
        this.login = login;
        this.env = env;
        this.group = group;
        this.admins = admins;
        this.backend = backend;
        this.err = err;
        tokens =
                new GateLoginTokens(
                        GateFiles.directory(env).resolve("login-tokens"), Clock.systemUTC());
    }

    static void run(
            final List<String> args,
            final Map<String, String> env,
            final PrintStream out,
            final PrintStream err)
            throws Failure {
        if (args.equals(List.of("--prepare"))) {
            GatePreparation.run(env, out);
            return;
        }
        if (!args.isEmpty()) {
            throw new Failure(Exit.USAGE, "usage: gate [--prepare]");
        }
        try {
            final var inherited = System.inheritedChannel();
            if (!(inherited instanceof SocketChannel socket)
                    || !socket.isConnected()
                    || !(socket.getRemoteAddress() instanceof UnixDomainSocketAddress)) {
                throw new Failure(Exit.CONFIG, "gate requires a connected inherited Unix socket");
            }
            try (socket) {
                final String caller =
                        socket.getOption(ExtendedSocketOptions.SO_PEERCRED).user().getName();
                new Gate(
                                caller,
                                env,
                                Path.of(
                                        env.getOrDefault(
                                                "PORELO_MATRIX_GATE_GROUP_FILE", "/etc/group")),
                                Path.of(
                                        env.getOrDefault(
                                                "PORELO_MATRIX_GATE_ADMINS_DIR",
                                                "/etc/porelo-host/matrix")),
                                new GateHomeserver(env),
                                err)
                        .serve(
                                new BufferedInputStream(Channels.newInputStream(socket)),
                                Channels.newOutputStream(socket),
                                socket);
            }
        } catch (final IOException | UnsupportedOperationException ex) {
            throw new Failure(Exit.CONFIG, "gate cannot use its inherited Unix socket", ex);
        }
    }

    void serve(final InputStream in, final OutputStream out) throws IOException {
        serve(in, out, null);
    }

    /**
     * With {@code connection} closed when a request does not arrive whole in {@link #IDLE}: a
     * connection held open, by a caller long since out of the group too, holds a process and one of
     * the socket's connections.
     */
    void serve(final InputStream in, final OutputStream out, final @Nullable Closeable connection)
            throws IOException {
        for (; ; ) {
            final GateRequest request;
            final @Nullable Thread watch = connection == null ? null : idle(connection);
            try {
                request = GateRequest.read(in);
            } catch (final IOException ex) {
                if (watch != null && !watch.isAlive()) {
                    // Closed for idling: nobody is there to answer.
                    return;
                }
                GateResponse.error(400, "M_BAD_JSON").write(out, true);
                refused("-", "-", 400);
                return;
            } finally {
                if (watch != null) {
                    watch.interrupt();
                }
            }
            if (request == null) {
                return;
            }
            final int status;
            try {
                final GateResponse answer = decide(request);
                if (answer == null) {
                    // The homeserver's answer is copied as it came, framed for a connection that
                    // ends with it, so this one ends with it too.
                    status = backend.forward(request, out);
                    if (status >= 400) {
                        refused(request.method(), request.path(), status);
                    }
                    return;
                } else {
                    status = answer.status();
                    answer.write(out, request.close() || status == 400);
                }
            } catch (final Failure | IOException ex) {
                GateResponse.error(502, "M_UNKNOWN").write(out, true);
                refused(request.method(), request.path(), 502);
                return;
            }
            if (status >= 400) {
                refused(request.method(), request.path(), status);
            }
            if (request.close() || status == 400) {
                return;
            }
        }
    }

    /** How long a request may take to arrive whole, a client's pause between two included. */
    static final java.time.Duration IDLE = java.time.Duration.ofMinutes(2);

    /** A thread that closes {@code connection} after {@link #IDLE}, unless interrupted first. */
    private static Thread idle(final Closeable connection) {
        return Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                Thread.sleep(IDLE);
                            } catch (final InterruptedException ex) {
                                return;
                            }
                            try {
                                connection.close();
                            } catch (final IOException ex) {
                                // Closed already.
                            }
                        });
    }

    private void refused(final String method, final String path, final int status) {
        final String safeLogin = login.matches("[a-zA-Z0-9_-]{1,64}") ? login : "invalid-login";
        // The target query and every body are deliberately absent, including parser diagnostics;
        // a decoded space or other character could not shift the columns of the line.
        final StringBuilder safePath = new StringBuilder();
        path.codePoints().forEach(c -> safePath.appendCodePoint(c <= 32 || c >= 127 ? '?' : c));
        err.println(safeLogin + " " + method + " " + safePath + " " + status);
    }

    private @Nullable GateResponse decide(final GateRequest request) throws IOException, Failure {
        // A browser page whose name was pointed at this machine's loopback reaches a forwarded
        // socket like a client of its own; only a loopback name is a client's.
        if (!GateRules.hostAllowed(request.host())) {
            return GateResponse.error(400, "M_UNRECOGNIZED");
        }
        // No answer of the gate's own is framed for a request that takes none.
        if (request.method().equals("HEAD")) {
            return GateResponse.error(400, "M_UNRECOGNIZED");
        }
        final boolean expelled =
                !Files.notExists(
                        LocalHomeserver.directory(env)
                                .resolve(Expel.DIRECTORY)
                                .resolve(GateRules.login(login) ? login : ".invalid"),
                        LinkOption.NOFOLLOW_LINKS);
        final String membership;
        try {
            membership = Files.readString(group);
        } catch (final IOException ex) {
            return forbidden();
        }
        if (!GateRules.admitted(login, membership, expelled)) {
            return forbidden();
        }
        final String path = request.path();
        final String method = request.method();
        final boolean wellKnown = method.equals("GET") && path.equals("/.well-known/matrix/client");
        if (!GateRules.clientPath(path) && !wellKnown) {
            return GateResponse.error(404, "M_UNRECOGNIZED");
        }
        final String token = request.token();
        String id = "";
        if (!token.isEmpty()) {
            final String hash = GateLoginTokens.hash(token);
            final String cached = identities.get(hash);
            if (cached == null) {
                final GateResponse identity = backend.exchange(token, "GET", WHOAMI, null);
                if (identity.status() != 200) {
                    return identity;
                }
                id = identity.json().path("user_id").asText("");
                if (!id.startsWith("@") || !GateRules.belongs(id, login)) {
                    return forbidden();
                }
                if (identities.size() == 64) {
                    identities.remove(identities.keySet().iterator().next());
                }
                identities.put(hash, id);
            } else {
                id = cached;
            }
        }
        if (wellKnown) {
            final ObjectNode body = JSON.createObjectNode();
            body.putObject("m.homeserver").put("base_url", "http://" + request.host());
            return new GateResponse(200, body);
        }
        if (method.equals("GET") && path.equals(CLIENT + "versions")) {
            return null;
        }
        final String endpoint = endpoint(path);
        if (method.equals("GET")
                && (path.equals(CLIENT + "v3/login") || path.equals(CLIENT + "r0/login"))) {
            final ObjectNode body = JSON.createObjectNode();
            final var flows = body.putArray("flows");
            flows.addObject()
                    .put("type", "m.login.sso")
                    .putArray("identity_providers")
                    .addObject()
                    .put("id", "porelo")
                    .put("name", "Your login on this host");
            flows.addObject().put("type", "m.login.token");
            flows.addObject().put("type", "m.login.password");
            return new GateResponse(200, body);
        }
        if (method.equals("POST") && endpoint.equals("login")) {
            return login(request);
        }
        if (method.equals("POST") && endpoint.equals("register")) {
            return register(request);
        }
        // The transport asks before it registers; only about the caller's own names.
        if (method.equals("GET") && endpoint.equals("register/available")) {
            return GateRules.belongs(request.parameter("username"), login)
                    ? null
                    : GateResponse.error(400, "M_INVALID_USERNAME");
        }
        if (method.equals("GET")
                && (path.equals(CLIENT + "v3/login/sso/redirect")
                        || path.equals(CLIENT + "v3/login/sso/redirect/porelo"))) {
            return sso(request);
        }
        if (token.isEmpty()) {
            return GateResponse.error(401, "M_MISSING_TOKEN");
        }
        return aliasRefusal(request, endpoint, id);
    }

    private static String endpoint(final String path) {
        if (!path.startsWith(CLIENT)) {
            return "";
        }
        final int slash = path.indexOf('/', CLIENT.length());
        // The homeserver routes a path with a trailing slash as the one without.
        String endpoint = slash < 0 ? "" : path.substring(slash + 1);
        while (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        return endpoint;
    }

    private GateResponse login(final GateRequest request) throws IOException, Failure {
        final JsonNode body;
        try {
            body = body(request);
        } catch (final IOException ex) {
            return GateResponse.error(400, "M_BAD_JSON");
        }
        final String type = body.path("type").asText("");
        if (type.equals("m.login.password")) {
            final String user =
                    body.path("identifier").path("user").asText(body.path("user").asText(""));
            if (!GateRules.belongs(user, login)
                    || body.has("user") && !GateRules.belongs(body.path("user").asText(""), login)
                    || body.has("identifier")
                            && (!body.path("identifier")
                                    .path("type")
                                    .asText("")
                                    .equals("m.id.user"))) {
                return forbidden();
            }
            return checkedLogin(backend.passwordLogin(request));
        }
        if (type.equals("m.login.token")) {
            if (!tokens.consume(body.path("token").asText(""), login)) {
                return forbidden();
            }
            final Administrator admin = administrator();
            final GateResponse issued =
                    backend.exchange(
                            admin.token(),
                            "POST",
                            adminLogin("@" + login + ":" + admin.server()),
                            JSON.createObjectNode());
            if (issued.status() != 200) {
                return issued;
            }
            final String token = issued.json().path("access_token").asText("");
            if (token.isEmpty()) {
                throw new IOException("homeserver answered no login token");
            }
            final GateResponse identity = backend.exchange(token, "GET", WHOAMI, null);
            if (identity.status() != 200
                    || !identity.json()
                            .path("user_id")
                            .asText("")
                            .equals("@" + login + ":" + admin.server())) {
                backend.exchange(token, "POST", CLIENT + "v3/logout", JSON.createObjectNode());
                return forbidden();
            }
            final ObjectNode answer =
                    session(((ObjectNode) identity.json()).deepCopy().put("access_token", token));
            answer.put("home_server", admin.server());
            return new GateResponse(200, answer);
        }
        return GateResponse.error(400, "M_UNKNOWN");
    }

    private GateResponse checkedLogin(final GateResponse response) throws IOException, Failure {
        if (response.status() != 200) {
            return response;
        }
        final JsonNode body = response.json();
        if (!body.path("user_id").asText("").startsWith("@")
                || !GateRules.belongs(body.path("user_id").asText(""), login)) {
            final String token = body.path("access_token").asText("");
            if (!token.isEmpty()) {
                backend.exchange(token, "POST", CLIENT + "v3/logout", JSON.createObjectNode());
            }
            return forbidden();
        }
        return response;
    }

    private GateResponse register(final GateRequest request) throws IOException, Failure {
        final JsonNode body;
        try {
            body = body(request);
        } catch (final IOException ex) {
            return GateResponse.error(400, "M_BAD_JSON");
        }
        final String username = body.path("username").asText("");
        if (!GateRules.belongs(username, login)) {
            return GateResponse.error(400, "M_INVALID_USERNAME");
        }
        if (!Files.exists(
                GateFiles.directory(env).resolve("admin-token"), LinkOption.NOFOLLOW_LINKS)) {
            return forbidden();
        }
        final Administrator admin = administrator();
        if (username.startsWith("@")
                && !username.equals("@" + GateRules.localpart(username) + ":" + admin.server())) {
            return GateResponse.error(400, "M_INVALID_USERNAME");
        }
        final String localpart = GateRules.localpart(username);
        final GateResponse available = availability(admin, localpart);
        if (available.status() != 200) {
            return available;
        }
        if (body.has("password")
                && (!body.path("password").isTextual()
                        || body.path("password").asText().isEmpty())) {
            return GateResponse.error(400, "M_BAD_JSON");
        }
        if (!body.has("password") || !body.hasNonNull("auth")) {
            final ObjectNode uia = JSON.createObjectNode();
            uia.putArray("flows").addObject().putArray("stages").add("m.login.dummy");
            uia.putObject("params");
            uia.put("session", "porelo-gate");
            return new GateResponse(401, uia);
        }
        if (!body.path("auth").path("type").asText("").equals("m.login.dummy")) {
            return GateResponse.error(400, "M_BAD_JSON");
        }
        final GateResponse created = create(admin, localpart, body.path("password").asText());
        if (created.status() != 200 && created.status() != 201) {
            return created;
        }
        final ObjectNode credentials =
                JSON.createObjectNode()
                        .put("type", "m.login.password")
                        .put("password", body.path("password").asText());
        credentials.putObject("identifier").put("type", "m.id.user").put("user", localpart);
        final GateResponse response =
                checkedLogin(backend.exchange("", "POST", CLIENT + "v3/login", credentials));
        return response.status() == 200
                ? new GateResponse(200, session(response.json()))
                : response;
    }

    private GateResponse sso(final GateRequest request) throws IOException, Failure {
        final String redirect = request.parameter("redirectUrl");
        if (!GateRules.redirectAllowed(redirect)) {
            return GateResponse.error(400, "M_INVALID_PARAM");
        }
        final Administrator admin = administrator();
        final GateResponse available = availability(admin, login);
        if (available.status() == 200) {
            final GateResponse created = create(admin, login, Accounts.secret());
            if (created.status() != 200 && created.status() != 201) {
                return created;
            }
        } else if (available.status() != 400
                || !available.json().path("errcode").asText("").equals("M_USER_IN_USE")) {
            return available;
        }
        final String token = tokens.issue(login);
        final int fragment = redirect.indexOf('#');
        final String base = fragment < 0 ? redirect : redirect.substring(0, fragment);
        final String suffix = fragment < 0 ? "" : redirect.substring(fragment);
        return GateResponse.redirect(
                base + (base.contains("?") ? "&" : "?") + "loginToken=" + token + suffix);
    }

    private GateResponse availability(final Administrator admin, final String localpart)
            throws IOException, Failure {
        final GateResponse answer =
                backend.exchange(
                        admin.token(),
                        "GET",
                        "/_synapse/admin/v1/username_available?username="
                                + MatrixClient.segment(localpart),
                        null);
        if (answer.status() == 200 && !answer.json().path("available").asBoolean(false)) {
            return GateResponse.error(400, "M_USER_IN_USE");
        }
        if (answer.status() == 400
                && answer.json().path("errcode").asText("").equals("M_USER_IN_USE")) {
            return GateResponse.error(400, "M_USER_IN_USE");
        }
        // What the administrator's API says is the gate's to know, not the caller's.
        return answer.status() == 200 ? answer : GateResponse.error(502, "M_UNKNOWN");
    }

    private GateResponse create(
            final Administrator admin, final String localpart, final String password)
            throws IOException, Failure {
        // Different gate processes must not turn a concurrent registration into a password reset.
        final Path directory = GateFiles.directory(env);
        synchronized (Gate.class) {
            try (var channel = GateFiles.lock(directory.resolve(".users.lock"));
                    var held = channel.lock()) {
                final GateResponse free = availability(admin, localpart);
                if (free.status() != 200) {
                    return free;
                }
                final GateResponse created =
                        backend.exchange(
                                admin.token(),
                                "PUT",
                                "/_synapse/admin/v2/users/"
                                        + MatrixClient.segment(
                                                "@" + localpart + ":" + admin.server()),
                                JSON.createObjectNode().put("password", password));
                // What the administrator's API says is the gate's to know, not the caller's.
                return created.status() == 200 || created.status() == 201
                        ? created
                        : GateResponse.error(502, "M_UNKNOWN");
            }
        }
    }

    private @Nullable GateResponse aliasRefusal(
            final GateRequest request, final String endpoint, final String id) {
        String alias = "";
        if (request.method().equals("POST") && endpoint.equals("createRoom")) {
            try {
                alias = body(request).path("room_alias_name").asText("");
            } catch (final IOException ex) {
                return GateResponse.error(400, "M_BAD_JSON");
            }
        } else if (request.method().equals("PUT") && endpoint.startsWith("directory/room/")) {
            final String full = endpoint.substring("directory/room/".length());
            final int colon = full.indexOf(':');
            if (!full.startsWith("#") || colon < 2) {
                return forbidden();
            }
            alias = full.substring(1, colon);
        }
        if (!alias.startsWith("porelo-")) {
            return null;
        }
        final String project = alias.substring("porelo-".length());
        if (!GateRules.project(project)) {
            return forbidden();
        }
        final Path file = admins.resolve(project + ".admins");
        try {
            final List<String> allowed =
                    Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
            return GateRules.aliasAllowed(alias, id, login, allowed) ? null : forbidden();
        } catch (final IOException ex) {
            return forbidden();
        }
    }

    private record Administrator(String token, String server) {
        @Override
        public String toString() {
            return "Administrator";
        }
    }

    private Administrator administrator() throws IOException, Failure {
        final String token = GateFiles.readToken(GateFiles.directory(env).resolve("admin-token"));
        final GateResponse identity = backend.exchange(token, "GET", WHOAMI, null);
        if (identity.status() != 200) {
            throw new IOException("the gate administrator is not available");
        }
        final String id = identity.json().path("user_id").asText("");
        if (!id.startsWith("@porelo:") || id.length() <= "@porelo:".length()) {
            throw new IOException("the gate token is not the administrator's");
        }
        return new Administrator(token, id.substring("@porelo:".length()));
    }

    private static String adminLogin(final String id) {
        return "/_synapse/admin/v1/users/" + MatrixClient.segment(id) + "/login";
    }

    private static ObjectNode session(final JsonNode body) throws IOException {
        final ObjectNode answer = JSON.createObjectNode();
        for (final String key : List.of("user_id", "access_token", "device_id")) {
            if (!body.path(key).isTextual() || body.path(key).asText().isEmpty()) {
                throw new IOException("homeserver answered an incomplete session");
            }
            answer.put(key, body.path(key).asText());
        }
        return answer;
    }

    private static JsonNode body(final GateRequest request) throws IOException {
        final JsonNode body = JSON.readTree(request.body());
        if (body == null || !body.isObject()) {
            throw new IOException("request body is not a JSON object");
        }
        return body;
    }

    private static GateResponse forbidden() {
        return GateResponse.error(403, "M_FORBIDDEN");
    }
}
