package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards against missing addressed lines or waking for another agent's work. */
class MeantForTest {

    private static final String SELF = "@core:localhost";
    private static final String OTHER = "@matrix:localhost";
    private static final Map<String, String> MEMBERS =
            Map.of(SELF, "Agent Core", OTHER, "Agent Matrix", "@person:localhost", "Carol");

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Agent Core: hello",
                "(cc Agent Core): hello",
                "Agent Smith, Agent Core: hello",
                "All agents: hello",
                "aLL AGENTS: hello",
                "hello",
                "Status: Agent Core is in the body",
                "Agent Unknown: hello",
                "Agent core: hello",
                "Agent Corey: hello"
            })
    void addressesSelfOrEverybody(final String body) {
        assertThat(MeantFor.line(event(body), SELF, MEMBERS))
                .as("addressed or unaddressed text is read")
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Agent Matrix: Agent Core is in the body",
                "Agent Matrix, Agent Corey: hello",
                "Agent Matrix, xAgent Core: hello",
                "Agent Matrix, Agent core: hello",
                "Agent Matrix: hello\nAgent Core: second line"
            })
    void leavesOtherAgentsLinesAlone(final String body) {
        assertThat(MeantFor.line(event(body), SELF, MEMBERS))
                .as("only another member is named in the head")
                .isFalse();
    }

    @Test
    void aPillAddressesItsMemberEvenWhenTheHeadNamesAnother() {
        final ObjectNode event = event("Agent Matrix: hello");
        event.withObject("content").putObject("m.mentions").putArray("user_ids").add(SELF);
        assertThat(MeantFor.line(event, SELF, MEMBERS))
                .as("a Matrix pill names the account")
                .isTrue();
    }

    @Test
    void aPillForAnotherMemberDoesNotAddressEverybody() {
        final ObjectNode event = event("hello");
        event.withObject("content").putObject("m.mentions").putArray("user_ids").add(OTHER);
        assertThat(MeantFor.line(event, SELF, MEMBERS))
                .as("a member's pill is an explicit addressee")
                .isFalse();
    }

    @Test
    void aRoomMentionAddressesEverybody() {
        final ObjectNode event = event("Agent Matrix: hello");
        event.withObject("content").putObject("m.mentions").put("room", true);
        assertThat(MeantFor.line(event, SELF, MEMBERS))
                .as("a room mention addresses everybody")
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Agent Core: hello", "All agents: hello", "hello"})
    void ownLinesNeverAddressSelf(final String body) {
        assertThat(MeantFor.line(event(body).put("sender", SELF), SELF, MEMBERS))
                .as("own text cannot wake or block its writer")
                .isFalse();
    }

    /** A writer's signature must not hide an otherwise unaddressed announcement. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aWriterNamingOnlyThemselvesAddressesEverybodyElse(final boolean pill) {
        final ObjectNode line = event(pill ? "done" : "Agent Matrix: done").put("sender", OTHER);
        if (pill) {
            line.withObject("content").putObject("m.mentions").putArray("user_ids").add(OTHER);
        }
        assertThat(MeantFor.line(line, SELF, MEMBERS))
                .as("a self-signature or self-pill names nobody else")
                .isTrue();
    }

    /** A post must not notify its own writer through a signature in the head. */
    @Test
    void aPostsMentionsExcludeItsWriter() {
        assertThat(MeantFor.post("Agent Core, Agent Matrix: done", MEMBERS, SELF).users())
                .as("only the other named member is mentioned")
                .containsExactly(OTHER);
    }

    /** Explicit addressees still constrain a line after its writer is removed. */
    @Test
    void aWriterNamingThemselvesAndAnotherMemberAddressesOnlyThatMember() {
        final ObjectNode line = event("Agent Matrix: done").put("sender", OTHER);
        line.withObject("content")
                .putObject("m.mentions")
                .putArray("user_ids")
                .add("@person:localhost");
        assertThat(MeantFor.line(line, SELF, MEMBERS))
                .as("a different member's pill stays addressed")
                .isFalse();
    }

    /** Broadcast substrings must not override another member's explicit addressee. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "small agents",
                "overall agents_count",
                "All agents_count",
                "xAll agents",
                "All agents2"
            })
    void aBroadcastMustBeAWholePhrase(final String phrase) {
        assertThat(MeantFor.head("Agent Matrix, " + phrase + ": hello", MEMBERS).room())
                .as("only a whole broadcast phrase mentions the room")
                .isFalse();
        assertThat(MeantFor.line(event("Agent Matrix, " + phrase + ": hello"), SELF, MEMBERS))
                .as("another agent's work must not wake this reader")
                .isFalse();
    }

    /** A long first line is prose even when a member's name precedes its colon. */
    @Test
    void aColonBeyondTheHeadLimitLeavesNobodyNamed() {
        final String prose = "Agent Matrix " + "x".repeat(300) + ": hello";
        assertThat(MeantFor.head(prose, MEMBERS).users())
                .as("an early name does not turn long prose into a head")
                .isEmpty();
        assertThat(MeantFor.line(event(prose), SELF, MEMBERS))
                .as("prose with no head is for everybody")
                .isTrue();
        assertThat(MeantFor.head("All agents " + "x".repeat(300) + ": hello", MEMBERS).room())
                .as("long prose has no explicit room mention")
                .isFalse();
    }

    /** Supplementary characters must not shift the protocol's head boundary. */
    @Test
    void theColonMustBeWithinTheFirstThreeHundredCodePoints() {
        assertThat(MeantFor.head("😀".repeat(288) + " Agent Core:", MEMBERS).users())
                .as("the colon at code point 300 terminates a head")
                .containsExactly(SELF);
        assertThat(MeantFor.head("😀".repeat(289) + " Agent Core:", MEMBERS).users())
                .as("the colon at code point 301 belongs to prose")
                .isEmpty();
    }

    @Test
    void extractsOnlyWholeMemberNamesFromTheBoundedHead() {
        assertThat(MeantFor.head("Agent Matrix, (cc Agent Core): hello", MEMBERS).users())
                .as("both whole names become Matrix mentions")
                .containsExactlyInAnyOrder(SELF, OTHER);
        assertThat(MeantFor.head("Agent Matrix, Agent Corey: Agent Core", MEMBERS).users())
                .as("neither a prefix nor the body names Core")
                .containsExactly(OTHER);
        assertThat(MeantFor.head("Agent Core has no colon", MEMBERS).users())
                .as("no colon means no head")
                .isEmpty();
        assertThat(MeantFor.head("x".repeat(300) + " Agent Core:", MEMBERS).users())
                .as("a colon beyond the head limit gives no head")
                .isEmpty();
        assertThat(MeantFor.head("All AGENTS: hello", MEMBERS).room())
                .as("the room mention ignores case")
                .isTrue();
        assertThat(MeantFor.head("Carol: hello", MEMBERS).users())
                .as("ordinary display names are not agent names")
                .isEmpty();
    }

    /** A text event from another room member. */
    private static ObjectNode event(final String body) {
        final ObjectNode event =
                MatrixClient.JSON
                        .createObjectNode()
                        .put("sender", "@person:localhost")
                        .put("type", "m.room.message");
        event.putObject("content").put("msgtype", "m.text").put("body", body);
        return event;
    }
}
