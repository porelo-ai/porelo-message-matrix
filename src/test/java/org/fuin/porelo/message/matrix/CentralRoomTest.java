package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which machines were in the project's room, and which accounts are a dropped machine's. */
class CentralRoomTest {

    private static Map<String, String> members() {
        final Map<String, String> members = new LinkedHashMap<>();
        members.put("@carol:x", "join");
        members.put("@task.hosta:x", "join");
        members.put("@porelo.hosta:x", "join");
        members.put("@gone.hosta:x", "leave");
        members.put("@porelo-relay-demo.hostb:x", "invite");
        members.put("@porelo.hostc:x", "leave");
        members.put("@porelo.hoste:x", "leave");
        members.put("@porelo-relay-demo.hoste:x", "leave");
        members.put("@t.hoste:x", "join");
        members.put("@job.x.hosta:x", "join");
        members.put("@porelo.x.hosta:x", "leave");
        members.put("@porelo-relay-demo.v2.hostf:x", "join");
        members.put("@porelo.hostd:elsewhere", "join");
        return members;
    }

    /**
     * Harm guarded: a machine whose {@code @porelo} and relay left while its tasks stayed is not
     * seen, so the list never takes its tasks out; or another project's relay, its name holding a
     * dot, reads as a machine and is taken out.
     */
    @Test
    @DisplayName(
            "A machine was in the room by its @porelo of any membership on this server, never by a relay's name")
    void machinesEverInTheRoom() {
        assertThat(CentralRoom.machines(members(), "x"))
                .as("the machines ever in the room")
                .containsExactly("hosta", "hostc", "hoste", "x.hosta");
    }

    /**
     * Harm guarded: a dropped machine's tasks stay once its {@code @porelo} left, or dropping one
     * machine kicks an account that left already or another machine's.
     */
    @Test
    @DisplayName(
            "Dropped machines' accounts in the room are theirs alone, each machine's @porelo first")
    void droppedMachinesAccounts() {
        assertThat(
                        CentralRoom.accounts(
                                members(),
                                new TreeSet<>(Set.of("hosta", "hoste")),
                                Set.of("admin", "x.hosta"),
                                "demo",
                                "x"))
                .as("the accounts of hosta and hoste, never one the kept x.hosta could claim")
                .containsExactly("@porelo.hosta:x", "@task.hosta:x", "@t.hoste:x");
    }

    /**
     * Harm guarded: with {@code hosta} and {@code x.hosta} both dropped, {@code x.hosta}'s task is
     * kicked while its {@code @porelo} is still in to invite it back.
     */
    @Test
    @DisplayName("Every dropped machine's @porelo goes before any of their other accounts")
    void everyDroppedPoreloFirst() {
        final Map<String, String> members = new LinkedHashMap<>();
        members.put("@job.x.hosta:x", "join");
        members.put("@porelo.x.hosta:x", "join");
        members.put("@porelo.hosta:x", "join");

        assertThat(
                        CentralRoom.accounts(
                                members,
                                new TreeSet<>(Set.of("hosta", "x.hosta")),
                                Set.of("admin"),
                                "demo",
                                "x"))
                .containsExactly("@porelo.hosta:x", "@porelo.x.hosta:x", "@job.x.hosta:x");
    }

    /**
     * Harm guarded: a machine dropped whose name ends as a kept one's ({@code x.dev} beside {@code
     * dev}) takes the kept machine's tasks and relay out, or keeps its own @porelo in the room.
     */
    @Test
    @DisplayName(
            "A kept machine's accounts are never touched, and a dropped one's @porelo and relay are its own")
    void aKeptMachinesAccountsAreNeverTouched() {
        final Map<String, String> members = new LinkedHashMap<>();
        members.put("@porelo.x.dev:x", "invite");
        members.put("@porelo-relay-demo.x.dev:x", "join");
        members.put("@t.x.dev:x", "join");

        assertThat(CentralRoom.accounts(members, Set.of("x.dev"), Set.of("dev"), "demo", "x"))
                .as("in project demo: x.dev's @porelo and relay, never the task dev could claim")
                .containsExactly("@porelo.x.dev:x", "@porelo-relay-demo.x.dev:x");
        assertThat(CentralRoom.accounts(members, Set.of("x.dev"), Set.of("dev"), "demo.x", "x"))
                .as("in project demo.x, the relay is dev's own")
                .containsExactly("@porelo.x.dev:x");
    }
}
