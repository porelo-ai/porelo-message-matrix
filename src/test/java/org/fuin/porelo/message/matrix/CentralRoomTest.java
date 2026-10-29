package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which machines are in the project's room, and which accounts are a dropped machine's. */
class CentralRoomTest {

    private static Map<String, String> members() {
        final Map<String, String> members = new LinkedHashMap<>();
        members.put("@carol:x", "join");
        members.put("@porelo.hosta:x", "join");
        members.put("@task.hosta:x", "join");
        members.put("@gone.hosta:x", "leave");
        members.put("@porelo.x.hosta:x", "join");
        members.put("@task.x.hosta:x", "invite");
        members.put("@porelo-relay-demo.hostb:x", "invite");
        members.put("@porelo.hostc:x", "leave");
        members.put("@porelo.hostd:elsewhere", "join");
        return members;
    }

    /**
     * Harm guarded: a machine whose {@code @porelo} left while its tasks stayed, or one only
     * invited, is not seen, and the list never takes its accounts out.
     */
    @Test
    @DisplayName(
            "A machine is in the room by its @porelo or its relay, invited or joined, on this server")
    void machinesInTheRoom() {
        assertThat(CentralRoom.present(members(), "demo", "x"))
                .as("the machines in the room")
                .containsExactly("hosta", "hostb", "x.hosta");
    }

    /**
     * Harm guarded: dropping one machine kicks another's tasks whose names end alike, a person, or
     * an account that left already.
     */
    @Test
    @DisplayName(
            "A dropped machine's accounts are its own alone, @porelo first, and never a longer machine's")
    void aDroppedMachinesAccounts() {
        assertThat(CentralRoom.accounts(members(), "hosta", "x", Set.of("hosta", "x.hosta")))
                .as("hosta's accounts")
                .containsExactly("@porelo.hosta:x", "@task.hosta:x");
    }
}
