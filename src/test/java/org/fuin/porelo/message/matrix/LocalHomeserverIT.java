package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The account's own homeserver published for another machine, against the real homeserver run as
 * the account's own: TLS with the account's CA beside the plain loopback listener this machine's
 * transport keeps using. The forwarding from the VPN's address is systemd's, measured on the VM.
 */
class LocalHomeserverIT {

    @TempDir private Path dir;

    private ProcessUnit unit;

    private Map<String, String> env;

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        final Path binary = ProcessUnit.binary(Files.createDirectories(dir.resolve("bin")));
        env = new HashMap<>();
        env.put("HOME", dir.resolve("home").toString());
        env.put("XDG_CONFIG_HOME", dir.resolve("config").toString());
        env.put("XDG_STATE_HOME", dir.resolve("state").toString());
        unit = new ProcessUnit(env, binary, dir.resolve("unit"));
    }

    @AfterEach
    void tearDown() throws Failure {
        unit.close();
    }

    /**
     * Harm guarded: a client on the other machine would hand its tokens to whoever answers, or the
     * listener refuses the client that holds the CA.
     */
    @Test
    @DisplayName(
            "Published, the homeserver serves TLS to a client with the account's CA, refuses one without it, and keeps its plain loopback listener")
    void servesTlsWithTheAccountsCa() throws Exception {
        final Path conf = Files.createDirectories(LocalHomeserver.directory(env));
        Files.writeString(
                conf.resolve("homeserver.conf"),
                LocalHomeserver.PUBLISH + "=127.0.0.1:8448\n",
                StandardCharsets.UTF_8);

        final JsonNode setup = lifecycle("setup", "--project", "demo");
        assertThat(setup.path("conversation").asText())
                .as("setup over the plain loopback listener, as before")
                .startsWith("!");

        final int port = LocalHomeserver.port(conf.resolve("homeserver.conf"));
        final URI https = URI.create("https://127.0.0.1:" + port);
        final Path ca = conf.resolve(Certificate.CA_CERTIFICATE);
        assertThat(
                        new MatrixClient(new Config(https, "", ca, true))
                                .exchange("GET", "/_matrix/client/versions", null)
                                .status())
                .as("a client with the CA file completes the handshake and is answered")
                .isEqualTo(200);
        assertThatThrownBy(
                        () ->
                                new MatrixClient(new Config(https, "", null, true))
                                        .exchange("GET", "/_matrix/client/versions", null))
                .as("a client without it refuses the certificate")
                .isInstanceOf(Failure.class)
                .extracting(failure -> ((Failure) failure).exitCode())
                .isEqualTo(Exit.CONFIG);
    }

    /**
     * Harm guarded: the program the unit runs before each start lacks what it needs to make the
     * certificate, and the published homeserver never starts on a machine.
     */
    @Test
    @DisplayName(
            "The transport's own program makes the CA and a server certificate for the published address before a start")
    void theProgramMakesTheCertificate() throws Exception {
        final Path conf = Files.createDirectories(LocalHomeserver.directory(env));
        Files.writeString(
                conf.resolve("homeserver.conf"),
                LocalHomeserver.PUBLISH + "=192.0.2.2:8448\n",
                StandardCharsets.UTF_8);

        final Transport.Result result = Transport.run(env, "homeserver-token");

        assertThat(result.exit()).as("homeserver-token: %s", result.err()).isEqualTo(Exit.OK);
        final java.security.cert.CertificateFactory x509 =
                java.security.cert.CertificateFactory.getInstance("X.509");
        final java.security.cert.X509Certificate ca =
                (java.security.cert.X509Certificate)
                        x509.generateCertificate(
                                Files.newInputStream(conf.resolve(Certificate.CA_CERTIFICATE)));
        final java.security.cert.X509Certificate server =
                (java.security.cert.X509Certificate)
                        x509.generateCertificate(
                                Files.newInputStream(
                                        conf.resolve(Certificate.TLS)
                                                .resolve(Certificate.SERVER_CERTIFICATE)));
        server.verify(ca.getPublicKey());
        assertThat(server.getSubjectAlternativeNames())
                .as("the server certificate the program made names the published address")
                .containsExactly(List.of(7, "192.0.2.2"));
        assertThat(Files.readString(conf.resolve(Publish.TLS_ENV)))
                .as("the program wrote the TLS settings the unit hands podman")
                .contains("TUWUNEL_TLS__DUAL_PROTOCOL=true");
    }

    /** Harm guarded: a machine that never publishes serves a listener nobody asked for. */
    @Test
    @DisplayName("Unpublished, the homeserver serves no TLS and its loopback listener is plain")
    void servesNoTlsUnpublished() throws Exception {
        lifecycle("setup", "--project", "demo");
        final Path conf = LocalHomeserver.directory(env);
        final int port = LocalHomeserver.port(conf.resolve("homeserver.conf"));

        assertThat(conf.resolve(Certificate.TLS).resolve(Certificate.SERVER_KEY))
                .as("no server key is made for a homeserver nobody else reaches")
                .doesNotExist();
        assertThatThrownBy(
                        () ->
                                new MatrixClient(
                                                new Config(
                                                        URI.create("https://127.0.0.1:" + port),
                                                        "",
                                                        null,
                                                        false))
                                        .exchange("GET", "/_matrix/client/versions", null))
                .as("nothing answers TLS on the loopback port")
                .isInstanceOf(Failure.class);
    }

    /**
     * Harm guarded: a dropped machine's token still registers accounts for whoever holds it, or
     * this machine's own setup breaks once other machines have tokens.
     */
    @Test
    @DisplayName(
            "A machine's token registers until it is revoked, while the local token and setup keep working")
    void aMachinesTokenRegistersUntilItIsRevoked() throws Exception {
        // As Porelo keeps it: the administrator's token from the first setup, for the next.
        lifecycle("setup", "--project", "demo")
                .path("account")
                .properties()
                .forEach(entry -> env.put(entry.getKey(), entry.getValue().asText()));
        final String local = LocalHomeserver.registrationToken(env);
        final int port =
                LocalHomeserver.port(LocalHomeserver.directory(env).resolve("homeserver.conf"));
        final MatrixClient nobody =
                new MatrixClient(
                        new Config(URI.create("http://127.0.0.1:" + port), "", null, true));

        final String token = MachineTokens.issue(env, "hosta");
        assertThat(MachineTokens.issue(env, "hosta"))
                .as("issued again, the machine keeps its token")
                .isEqualTo(token);
        assertThat(token).as("a token of its own").isNotEqualTo(local);
        assertThat(LocalHomeserver.registrationToken(env))
                .as("this machine's own token is still the one it reads")
                .isEqualTo(local);
        assertThat(Accounts.register(nobody, "porelo.hosta", Accounts.password(), token).userId())
                .as("the machine registers its account with its token")
                .startsWith("@porelo.hosta:");
        assertThat(lifecycle("setup", "--project", "demo").path("conversation").asText())
                .as("setup goes on with the local token beside the machine's")
                .startsWith("!");

        assertThat(MachineTokens.revoke(env, "hosta")).as("it had a token").isTrue();

        assertThatThrownBy(() -> Accounts.register(nobody, "intruder", Accounts.password(), token))
                .as("the revoked token registers nobody")
                .isInstanceOf(Failure.class);
        assertThat(Accounts.register(nobody, "worker", Accounts.password(), local).userId())
                .as("the local token still registers")
                .startsWith("@worker:");
    }

    /**
     * Harm guarded: another machine cannot reach the room it was admitted to, or a machine
     * dismissed keeps registering accounts and keeps its place in the room.
     */
    @Test
    @DisplayName(
            "Admitted, another machine sets up in this machine's room over TLS; dismissed, its token registers nobody and its accounts are out")
    void anotherMachineIsAdmittedAndDismissed() throws Exception {
        final Path conf = Files.createDirectories(LocalHomeserver.directory(env));
        Files.writeString(
                conf.resolve("homeserver.conf"),
                LocalHomeserver.PUBLISH + "=127.0.0.1:8448\n",
                StandardCharsets.UTF_8);
        final JsonNode setup = lifecycle("setup", "--project", "demo");
        keep(env, setup.path("account"));
        keep(env, setup.path("secrets"));
        final int port = LocalHomeserver.port(conf.resolve("homeserver.conf"));

        final JsonNode admitted = lifecycle("admit", "--project", "demo", "--machine", "hostb");
        final JsonNode admission = admitted.path("admission");
        final String token = admitted.path("secrets").path("registration_token").asText();
        assertThat(admission.path("room").asText())
                .as("the room is the project's")
                .isEqualTo(setup.path("conversation").asText());
        assertThat(admission.path("machine").asText())
                .as("the machine admitted")
                .isEqualTo("hostb");
        assertThat(admission.path("homeserver").asText())
                .as("the published address, over https")
                .isEqualTo("https://127.0.0.1:8448");
        assertThat(
                        lifecycle("admit", "--project", "demo", "--machine", "hostb")
                                .path("secrets")
                                .path("registration_token")
                                .asText())
                .as("admitted again, the same token")
                .isEqualTo(token);
        assertThat(admitted.path("admission").has("registration_token"))
                .as("the token is only among the secrets")
                .isFalse();

        // The other machine, as Porelo sets it up there: the homeserver over TLS on this port
        // (the published address is the socket's), the CA's text as a setting, the token in the
        // environment.
        final Path other = Files.createDirectories(dir.resolve("hostb"));
        final Map<String, String> hostb = new HashMap<>();
        hostb.put("HOME", other.resolve("home").toString());
        hostb.put("XDG_CONFIG_HOME", other.resolve("config").toString());
        hostb.put("XDG_STATE_HOME", other.resolve("state").toString());
        hostb.put(Config.REGISTRATION_TOKEN, token);
        final String settings =
                MatrixClient.JSON
                        .createObjectNode()
                        .put("homeserver", "https://127.0.0.1:" + port)
                        .put("room", admission.path("room").asText())
                        .put("ca_certificate", admission.path("ca_certificate").asText())
                        .toString();
        final JsonNode first =
                lifecycle(hostb, settings, "setup", "--project", "demo", "--login", "hostb");
        assertThat(first.path("admitted").asBoolean(false))
                .as("admitted before it registered, the machine joins at its first setup")
                .isTrue();
        keep(hostb, first.path("account"));
        final JsonNode second =
                lifecycle(hostb, settings, "setup", "--project", "demo", "--login", "hostb");
        assertThat(second.path("admitted").asBoolean(false))
                .as("the admitted machine's second setup joins the room")
                .isTrue();
        keep(hostb, second.path("account"));
        lifecycle(
                hostb,
                settings,
                "enroll",
                "--project",
                "demo",
                "--task",
                "worker",
                "--login",
                "hostb");
        // Renamed, the task is still the machine's: its name says so, and nothing else can.
        lifecycle(
                hostb,
                settings,
                "enroll",
                "--project",
                "demo",
                "--task",
                "worker",
                "--display",
                "Grace",
                "--login",
                "hostb");
        assertThat(
                        lifecycle("admit", "--project", "demo", "--machine", "hostb")
                                .path("secrets")
                                .path("registration_token")
                                .asText())
                .as(
                        "admitted again while its accounts are in the room, the machine keeps its token")
                .isEqualTo(token);
        // While hostb is admitted, no name of this machine's may end as its accounts do.
        for (final String[] taken :
                List.of(
                        new String[] {"join", "--project", "demo", "--person", "carol.hostb"},
                        new String[] {"enroll", "--project", "demo", "--task", "job.hostb"},
                        new String[] {"admit", "--project", "demo", "--machine", "sub.hostb"},
                        new String[] {"setup", "--project", "demo.hostb"})) {
            assertThatThrownBy(() -> lifecycle(taken))
                    .as("%s is refused while hostb is admitted", String.join(" ", taken))
                    .isInstanceOfSatisfying(
                            Failure.class,
                            failure ->
                                    assertThat(failure.exitCode())
                                            .as("a name that would be taken for the machine's")
                                            .isEqualTo(Exit.CONFIG));
        }
        // A person parted from the room whom the machine invited back: not the machine's.
        lifecycle("join", "--project", "demo", "--person", "dave");
        lifecycle("part", "--project", "demo", "--person", "dave");
        new MatrixClient(
                        new Config(
                                URI.create("https://127.0.0.1:" + port),
                                hostb.get(Config.ADMIN_TOKEN),
                                Settings.read(
                                                new ByteArrayInputStream(
                                                        settings.getBytes(StandardCharsets.UTF_8)))
                                        .materialized(hostb)
                                        .caFile(),
                                true))
                .post(
                        "/_matrix/client/v3/rooms/"
                                + MatrixClient.segment(admission.path("room").asText())
                                + "/invite",
                        MatrixClient.JSON.createObjectNode().put("user_id", "@dave:localhost"));

        final JsonNode dismissed = lifecycle("dismiss", "--project", "demo", "--machine", "hostb");

        assertThat(dismissed.path("removed"))
                .as("the token and exactly the machine's own accounts in the room")
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(
                        "the registration token of hostb",
                        "@porelo.hostb:localhost",
                        "@porelo-relay-demo.hostb:localhost",
                        "@worker.hostb:localhost");
        final MatrixClient nobody =
                new MatrixClient(
                        new Config(URI.create("http://127.0.0.1:" + port), "", null, true));
        assertThat(members(nobody, admission.path("room").asText()))
                .as(
                        "the machine's accounts are banned from the room, so none invites another back;"
                                + " this machine's people and relay stay")
                .containsEntry("@porelo.hostb:localhost", "ban")
                .containsEntry("@porelo-relay-demo.hostb:localhost", "ban")
                .containsEntry("@worker.hostb:localhost", "ban")
                .containsEntry("@dave:localhost", "invite")
                .containsEntry("@porelo-relay-demo:localhost", "join");
        assertThatThrownBy(() -> lifecycle("join", "--project", "demo", "--person", "worker.hostb"))
                .as("a dismissed task's account is no person's to take without --reset")
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure ->
                                assertThat(failure.exitCode())
                                        .as("an existing account")
                                        .isEqualTo(Exit.NOT_PERMITTED));
        assertThatThrownBy(() -> Accounts.register(nobody, "intruder", Accounts.password(), token))
                .as("the dismissed machine's token registers nobody")
                .isInstanceOf(Failure.class);
        assertThat(lifecycle("dismiss", "--project", "demo", "--machine", "hostb").path("removed"))
                .as("dismissed again, nothing is left")
                .isEmpty();

        lifecycle("admit", "--project", "demo", "--machine", "hostb");
        assertThat(members(nobody, admission.path("room").asText()))
                .as("admitted again, the machine's accounts may come back")
                .containsEntry("@porelo.hostb:localhost", "invite")
                .containsEntry("@worker.hostb:localhost", "leave");
        lifecycle("dismiss", "--project", "demo", "--machine", "hostb");
        assertThatThrownBy(() -> lifecycle("join", "--project", "demo", "--person", "worker.hostb"))
                .as(
                        "a ban lifted and the machine dismissed again: still no person's account to take")
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure ->
                                assertThat(failure.exitCode())
                                        .as("an existing account")
                                        .isEqualTo(Exit.NOT_PERMITTED));

        lifecycle("admit", "--project", "demo", "--machine", "hostb");
        assertThat(lifecycle("clear", "--project", "demo").path("kept"))
                .as(
                        "clearing the project leaves the admitted machine's accounts for its other projects")
                .anyMatch(
                        kept ->
                                kept.path("what")
                                                .asText()
                                                .equals("the account @porelo.hostb:localhost")
                                        && kept.path("why")
                                                .asText()
                                                .equals(
                                                        "an account of the admitted machine hostb"));
    }

    /**
     * Harm guarded: a machine is admitted under a name a person or task of this machine already
     * ends with, and dismissing it later puts them out.
     */
    @Test
    @DisplayName(
            "A first admit is refused while a member of the room ends as the machine's accounts would")
    void aMachineWhoseNameIsTakenIsRefused() throws Exception {
        final Path conf = Files.createDirectories(LocalHomeserver.directory(env));
        Files.writeString(
                conf.resolve("homeserver.conf"),
                LocalHomeserver.PUBLISH + "=127.0.0.1:8448\n",
                StandardCharsets.UTF_8);
        final JsonNode setup = lifecycle("setup", "--project", "demo");
        keep(env, setup.path("account"));
        keep(env, setup.path("secrets"));
        lifecycle("join", "--project", "demo", "--person", "erin.hostc");

        assertThatThrownBy(() -> lifecycle("admit", "--project", "demo", "--machine", "hostc"))
                .as("erin.hostc would be taken for one of hostc's accounts")
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure ->
                                assertThat(failure.exitCode())
                                        .as("the name is taken")
                                        .isEqualTo(Exit.CONFIG));
        assertThat(LocalHomeserver.directory(env).resolve("machine-tokens/hostc"))
                .as("no token is made for it")
                .doesNotExist();
        assertThat(lifecycle("dismiss", "--project", "demo", "--machine", "hostc").path("removed"))
                .as("a machine never admitted has nothing here to dismiss")
                .isEmpty();
        assertThat(members(nobody(), setup.path("conversation").asText()))
                .as("the person whose name ends so stays")
                .containsEntry("@erin.hostc:localhost", "join");

        // In another project's room too, a name taken blocks the first admission.
        lifecycle("setup", "--project", "q");
        lifecycle("join", "--project", "q", "--person", "erin.hoste");
        assertThatThrownBy(() -> lifecycle("admit", "--project", "demo", "--machine", "hoste"))
                .as("erin.hoste in project q would be taken for hoste's")
                .isInstanceOf(Failure.class);

        // Nested names either way round: y.hostd admitted, hostd is refused.
        lifecycle("admit", "--project", "demo", "--machine", "y.hostd");
        assertThatThrownBy(() -> lifecycle("admit", "--project", "demo", "--machine", "hostd"))
                .as("y.hostd's accounts end as hostd's would")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("the machine y.hostd is admitted already");
    }

    /**
     * Harm guarded: the machine that serves the room, named in the signed settings by its own
     * published name, takes the central path and loses its administrator for every project.
     */
    @Test
    @DisplayName(
            "Settings that name the account's own published homeserver keep its own path, administrator and room")
    void theOwnPublishedNameIsTheOwnHomeserver() throws Exception {
        final Path conf = Files.createDirectories(LocalHomeserver.directory(env));
        Files.writeString(
                conf.resolve("homeserver.conf"),
                LocalHomeserver.PUBLISH
                        + "=127.0.0.1:8448\n"
                        + LocalHomeserver.PUBLISH_NAME
                        + "=matrix.porelo.internal\n",
                StandardCharsets.UTF_8);
        final JsonNode local = lifecycle("setup", "--project", "demo");
        keep(env, local.path("account"));
        keep(env, local.path("secrets"));
        final int port = LocalHomeserver.port(conf.resolve("homeserver.conf"));
        final String settings =
                MatrixClient.JSON
                        .createObjectNode()
                        .put("homeserver", "https://matrix.porelo.internal:" + port)
                        .put("room", local.path("conversation").asText())
                        .put(
                                "ca_certificate",
                                Files.readString(conf.resolve(Certificate.CA_CERTIFICATE)))
                        .toString();

        final JsonNode named =
                lifecycle(
                        env,
                        settings.replace("matrix.porelo.internal", "Matrix.Porelo.Internal"),
                        "setup",
                        "--project",
                        "demo");

        assertThat(named.path("conversation").asText())
                .as("the same room")
                .isEqualTo(local.path("conversation").asText());
        assertThat(named.path("account").path(Config.ADMIN_TOKEN).asText())
                .as("the same administrator, not a machine account")
                .isEqualTo(local.path("account").path(Config.ADMIN_TOKEN).asText());
        assertThat(named.has("admitted")).as("no central setup").isFalse();
        assertThat(
                        lifecycle(
                                        env,
                                        settings,
                                        "join",
                                        "--project",
                                        "demo",
                                        "--person",
                                        "ada",
                                        "--token")
                                .path("login")
                                .path("room")
                                .asText())
                .as("people are still signed on here")
                .isEqualTo(local.path("conversation").asText());
        for (final String wrong :
                List.of(
                        settings.replace(local.path("conversation").asText(), "!another:localhost"),
                        settings.replace(":" + port + "\"", ":" + (port + 1) + "\""))) {
            assertThatThrownBy(() -> lifecycle(env, wrong, "setup", "--project", "demo"))
                    .as("another room or port than this homeserver's for the project is refused")
                    .isInstanceOfSatisfying(
                            Failure.class,
                            failure ->
                                    assertThat(failure.exitCode())
                                            .as("a configuration fault")
                                            .isEqualTo(Exit.CONFIG));
        }
        // Published by its name with no port of its own kept, nothing can tell which URL is its.
        Files.writeString(
                conf.resolve("homeserver.conf"),
                LocalHomeserver.PUBLISH
                        + "=127.0.0.1:8448\n"
                        + LocalHomeserver.PUBLISH_NAME
                        + "=matrix.porelo.internal\n",
                StandardCharsets.UTF_8);
        assertThatThrownBy(
                        () ->
                                lifecycle(
                                        env,
                                        settings.replace(":" + port + "\"", "\""),
                                        "setup",
                                        "--project",
                                        "demo"))
                .as("a URL without a port is not taken for a homeserver whose port is unknown")
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure ->
                                assertThat(failure.exitCode())
                                        .as("refused, never guessed")
                                        .isEqualTo(Exit.CONFIG));
        // A name kept without its address publishes nothing: the settings' homeserver is another's.
        Files.writeString(
                conf.resolve("homeserver.conf"),
                LocalHomeserver.PORT
                        + "="
                        + port
                        + "\n"
                        + LocalHomeserver.PUBLISH_NAME
                        + "=matrix.porelo.internal\n",
                StandardCharsets.UTF_8);
        assertThatThrownBy(() -> lifecycle(env, settings, "setup", "--project", "demo"))
                .as(
                        "without a published address this machine takes the central path, which needs its name")
                .hasMessageContaining(Config.LOGIN + " is not set");
    }

    /**
     * Harm guarded: a person taken off the host keeps an account that logs in or stays in a room, a
     * person's account becomes the homeserver's administrator, or another person's accounts go with
     * theirs.
     */
    @Test
    @DisplayName(
            "With no project, a machine is issued one token, expelled from every account and room with its token revoked, and refused again, and no other machine is touched")
    void issuesExpelsAndRevokesAMachineWithNoProject() throws Exception {
        // The first issue fails once the administrator is registered: its token is answered still.
        final Path blocked = LocalHomeserver.directory(env).resolve("machine-tokens");
        Files.createDirectories(blocked.getParent());
        Files.writeString(blocked, "");
        final ByteArrayOutputStream failed = new ByteArrayOutputStream();
        assertThatThrownBy(
                        () ->
                                MachineTokenVerb.run(
                                        List.of("issue", "hosta"),
                                        env,
                                        new PrintStream(failed, true, StandardCharsets.UTF_8),
                                        unit))
                .as("the first issue, failing after the administrator was registered")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("cannot make the registration token of hosta");
        final String admin =
                MatrixClient.JSON
                        .readTree(failed.toByteArray())
                        .path("account")
                        .path(Config.ADMIN_TOKEN)
                        .asText("");
        assertThat(admin).as("the failing issue answers the administrator's token").isNotBlank();
        Files.delete(blocked);
        env.put(Config.ADMIN_TOKEN, admin);
        final JsonNode first = machineToken("issue", "hosta");
        assertThat(first.has("account")).as("given the token, no second administrator").isFalse();
        final JsonNode again = machineToken("issue", "hosta");
        assertThat(again.path("token").asText())
                .as("issued again, the same token")
                .isEqualTo(first.path("token").asText());
        assertThat(again.has("account")).as("and no second administrator").isFalse();
        final String hostb = machineToken("issue", "hostb").path("token").asText();

        final MatrixClient nobody = nobody();
        assertThat(Accounts.whoami(nobody.as(admin)))
                .as("the administrator was registered before any machine")
                .startsWith("@porelo:");
        final String password = Accounts.password();
        final String tokenA = first.path("token").asText();
        final Accounts.Session poreloA =
                Accounts.register(nobody, "porelo.hosta", password, tokenA);
        Accounts.register(nobody, "task.hosta", password, tokenA);
        // Listed before @porelo.hosta, so the order answered is the verb's own.
        Accounts.register(nobody, "a.hosta", password, tokenA);
        // Registered by its holder, a machine's @porelo that refuses nothing.
        Accounts.register(nobody, "porelo.worker.hosta", password, tokenA);
        final Accounts.Session poreloB = Accounts.register(nobody, "porelo.hostb", password, hostb);
        final MatrixClient asB = nobody.as(poreloB.accessToken());
        final String room =
                asB.post(
                                "/_matrix/client/v3/createRoom",
                                MatrixClient.JSON
                                        .createObjectNode()
                                        .put("preset", "private_chat")
                                        .set(
                                                "invite",
                                                MatrixClient.JSON
                                                        .createArrayNode()
                                                        .add(poreloA.userId())))
                        .path("room_id")
                        .asText();
        nobody.as(poreloA.accessToken())
                .post(
                        "/_matrix/client/v3/join/" + MatrixClient.segment(room),
                        MatrixClient.JSON.createObjectNode());

        assertThat(machineToken("expel", "hostz").toString())
                .as("a machine the homeserver knows nothing of")
                .isEqualTo(
                        "{\"machine\":\"hostz\",\"known\":false,\"revoked\":false,\"expelled\":[]}");

        Accounts.register(nobody, "porelo.hostd", password, tokenA);
        assertThatThrownBy(() -> machineToken("issue", "hostd"))
                .as("a machine the files know nothing of, whose @porelo is listed")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("has accounts of hostd already");
        assertThatThrownBy(() -> machineToken("issue", "x.hostd"))
                .as("a machine ending as one listed by its @porelo only")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("would end alike");

        final String hostc = machineToken("issue", "hostc").path("token").asText();
        Accounts.register(nobody, "porelo.hostc", password, hostc);
        machineToken("revoke", "hostc");
        assertThatThrownBy(() -> machineToken("issue", "hostc"))
                .as("a machine revoked, whose accounts stay")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("hostc's token was revoked");
        assertThatThrownBy(() -> machineToken("issue", "x.hostc"))
                .as("a machine ending as one revoked")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("would end alike");
        assertThat(machineToken("expel", "hostc").path("expelled"))
                .as("a machine revoked before is expelled still")
                .extracting(JsonNode::asText)
                .containsExactly("@porelo.hostc:" + AdminRoom.server(admin(nobody)));

        final JsonNode expelled = machineToken("expel", "hosta");

        final String server = AdminRoom.server(admin(nobody));
        assertThat(expelled.path("expelled"))
                .as("every account of the machine, @porelo.hosta first")
                .extracting(JsonNode::asText)
                .containsExactly(
                        poreloA.userId(),
                        "@a.hosta:" + server,
                        "@porelo.worker.hosta:" + server,
                        "@task.hosta:" + server);
        assertThat(expelled.path("revoked").asBoolean()).as("its token goes with it").isTrue();
        assertThat(expelled.path("known").asBoolean()).as("known").isTrue();
        for (final String localpart : List.of("porelo.hosta", "task.hosta", "a.hosta")) {
            assertThatThrownBy(() -> Accounts.login(nobody, localpart, password))
                    .as("%s logs in no more", localpart)
                    .isInstanceOf(Failure.class);
        }
        assertThatThrownBy(() -> Accounts.whoami(nobody.as(poreloA.accessToken())))
                .as("its session ended")
                .isInstanceOf(Failure.class);
        final JsonNode member =
                asB.find(
                        "/_matrix/client/v3/rooms/"
                                + MatrixClient.segment(room)
                                + "/state/m.room.member/"
                                + MatrixClient.segment(poreloA.userId()));
        assertThat(member).as("the expelled account's membership").isNotNull();
        assertThat(member.path("membership").asText())
                .as("the expelled account left the room")
                .isEqualTo("leave");
        assertThat(Accounts.login(nobody, "porelo.hostb", password).userId())
                .as("the other machine's account still logs in")
                .isEqualTo(poreloB.userId());

        assertThat(machineToken("revoke", "hosta").path("revoked").asBoolean())
                .as("revoked after expel, no token is left")
                .isFalse();
        assertThatThrownBy(() -> Accounts.register(nobody, "intruder", Accounts.password(), tokenA))
                .as("the revoked token registers nobody")
                .isInstanceOf(Failure.class);
        assertThat(Accounts.register(nobody, "task.hostb", password, hostb).userId())
                .as("the other machine's token still registers")
                .startsWith("@task.hostb:");
        assertThatThrownBy(() -> machineToken("issue", "hosta"))
                .as("the expelled machine gets no token again")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("hosta was expelled");
    }

    private String admin(final MatrixClient nobody) throws Failure {
        return Accounts.whoami(nobody.as(env.get(Config.ADMIN_TOKEN)));
    }

    private JsonNode machineToken(final String... args) throws Failure, IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        MachineTokenVerb.run(
                List.of(args), env, new PrintStream(out, true, StandardCharsets.UTF_8), unit);
        return MatrixClient.JSON.readTree(out.toByteArray());
    }

    private MatrixClient nobody() throws Failure {
        final int port =
                LocalHomeserver.port(LocalHomeserver.directory(env).resolve("homeserver.conf"));
        return new MatrixClient(new Config(URI.create("http://127.0.0.1:" + port), "", null, true));
    }

    private Map<String, String> members(final MatrixClient nobody, final String room)
            throws Failure {
        final Map<String, String> members = new HashMap<>();
        for (final JsonNode member :
                nobody.as(env.get(Config.ADMIN_TOKEN))
                        .get("/_matrix/client/v3/rooms/" + MatrixClient.segment(room) + "/members")
                        .path("chunk")) {
            members.put(
                    member.path("state_key").asText(),
                    member.path("content").path("membership").asText());
        }
        return members;
    }

    private static void keep(final Map<String, String> into, final JsonNode values) {
        values.properties().forEach(entry -> into.put(entry.getKey(), entry.getValue().asText()));
    }

    private JsonNode lifecycle(
            final Map<String, String> on, final String settings, final String... args)
            throws Failure, IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Lifecycle.run(
                args[0],
                List.of(args).subList(1, args.length),
                on,
                new ByteArrayInputStream(settings.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(OutputStream.nullOutputStream()),
                unit);
        return MatrixClient.JSON.readTree(out.toByteArray());
    }

    private JsonNode lifecycle(final String... args) throws Failure, IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Lifecycle.run(
                args[0],
                List.of(args).subList(1, args.length),
                env,
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(OutputStream.nullOutputStream()),
                unit);
        return MatrixClient.JSON.readTree(out.toByteArray());
    }
}
