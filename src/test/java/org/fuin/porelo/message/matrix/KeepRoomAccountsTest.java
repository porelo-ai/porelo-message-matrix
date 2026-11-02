package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which logins were in the project's room, and which accounts are a dropped login's. */
class KeepRoomAccountsTest {

    private static Map<String, String> members() {
        final Map<String, String> members = new LinkedHashMap<>();
        members.put("@carol:x", "join");
        members.put("@hosta:x", "join");
        members.put("@task.hosta:x", "join");
        members.put("@porelo.hosta:x", "join");
        members.put("@gone.hosta:x", "leave");
        members.put("@porelo-relay-demo.hostb:x", "invite");
        members.put("@porelo.hostc:x", "leave");
        members.put("@porelo.hoste:x", "leave");
        members.put("@porelo-relay-demo.hoste:x", "leave");
        members.put("@t.hoste:x", "join");
        members.put("@job.x-hosta:x", "join");
        members.put("@porelo.x-hosta:x", "leave");
        members.put("@porelo-relay-demo.v2.hostf:x", "join");
        members.put("@porelo.hostd:elsewhere", "join");
        return members;
    }

    /**
     * Harm guarded: a login whose {@code @porelo} and relay left while its tasks stayed is not
     * seen, so the list never takes its tasks out; a dot in a project or task name must not be read
     * as part of the login.
     */
    @Test
    @DisplayName(
            "Any account identifies its login on this server, including a left provisioning account")
    void loginsEverInTheRoom() {
        assertThat(KeepRoom.logins(members(), "x"))
                .as("the logins ever in the room")
                .containsExactly("carol", "hosta", "hostb", "hostc", "hoste", "hostf", "x-hosta");
    }

    /**
     * Harm guarded: a dropped login's tasks stay once its {@code @porelo} left, or dropping one
     * login kicks an account that left already or another login's.
     */
    @Test
    @DisplayName(
            "Dropped logins' accounts in the room are theirs alone, each login's @porelo first")
    void droppedLoginsAccounts() {
        assertThat(
                        KeepRoom.accounts(
                                members(),
                                new TreeSet<>(Set.of("hosta", "hoste")),
                                Set.of("admin", "x-hosta"),
                                "x"))
                .as("the accounts of hosta and hoste, never one the kept x-hosta could claim")
                .containsExactly("@porelo.hosta:x", "@hosta:x", "@task.hosta:x", "@t.hoste:x");
    }

    /**
     * Harm guarded: with {@code hosta} and {@code x-hosta} both dropped, {@code x-hosta}'s task is
     * kicked while its {@code @porelo} is still in to invite it back.
     */
    @Test
    @DisplayName("Every dropped login's @porelo goes before any of their other accounts")
    void everyDroppedPoreloFirst() {
        final Map<String, String> members = new LinkedHashMap<>();
        members.put("@job.x-hosta:x", "join");
        members.put("@porelo.x-hosta:x", "join");
        members.put("@porelo.hosta:x", "join");

        assertThat(
                        KeepRoom.accounts(
                                members,
                                new TreeSet<>(Set.of("hosta", "x-hosta")),
                                Set.of("admin"),
                                "x"))
                .as("both provisioning accounts are removed before the task")
                .containsExactly("@porelo.hosta:x", "@porelo.x-hosta:x", "@job.x-hosta:x");
    }

    /**
     * Harm guarded: a login dropped whose name ends as a kept one's ({@code x-dev} beside {@code
     * dev}) takes the kept login's tasks and relay out, or keeps its own @porelo in the room.
     */
    @Test
    @DisplayName(
            "A kept login's accounts are never touched, and a dropped one's @porelo and relay are its own")
    void aKeptLoginsAccountsAreNeverTouched() {
        final Map<String, String> members = new LinkedHashMap<>();
        members.put("@porelo.x-dev:x", "invite");
        members.put("@porelo-relay-demo.x-dev:x", "join");
        members.put("@t.x-dev:x", "join");

        assertThat(KeepRoom.accounts(members, Set.of("x-dev"), Set.of("dev"), "x"))
                .as("x-dev owns its provisioning account, relay and task")
                .containsExactly("@porelo.x-dev:x", "@porelo-relay-demo.x-dev:x", "@t.x-dev:x");
        assertThat(KeepRoom.accounts(members, Set.of("dev"), Set.of("x-dev"), "x"))
                .as("dropping dev leaves every account of the kept x-dev")
                .isEmpty();
    }
}
