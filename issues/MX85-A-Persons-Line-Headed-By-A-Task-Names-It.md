# MX85 — A Person's Line Headed By A Task Names It

**Status:** Implementing; accepted by the operator, 2026-11-08. Found in a move to a host.
**Size:** S - one method in `Person`, one call in `Poll`, their tests, and the decision it revises; the person
file's `to` keeps its meaning and only names more accounts.

**What must be true.** A person who writes `Agent Pi: …` in the project's room, as typed text with no pill, gets
a person file whose `to` names the account shown as Agent Pi, so Porelo wakes that task.

## What happened

In the third move the operator wrote in porelo's room on a host. With the tasks shown as `Agent Pi` and so on
(Porelo passes the agent's name as `--display` at `enroll`), the operator's decision is that `Agent Pi: …` names
the task, as it does in the agents' own room. Today the transport fills `to` only from what the person's client
marked: `m.mentions`, else `matrix.to` links in the formatted body, plus the sender of the message replied to.
Typed text that only names a task gives `to: []`, and no task is woken.

## Plan

- **`Person.deliver` adds the accounts the first line's head names**, by the rule the room tool already keeps in
  `MeantFor.head`: the text before the first colon within the first 300 code points, and a member whose display
  name is `Agent <Word>` occurring there as a whole phrase, case-sensitive. They join the marked mentions, the
  `matrix.to` links and the replied-to sender, and the same removals follow: the writer, the polling account and the
  administrator.
- **The member names come from the room's `joined_members`**, asked once per room per poll and only when a person's
  line in that room is to be written; a direct chat is meant for its task already and asks nothing.
- **`All agents` is not marked**: what a person's line headed so means waits for the operator.
- **`doc/decisions.md`** ("A person's words reach the tasks; whom they are meant for is what Matrix states") and
  **`doc/transport.md`** ("Its `to`") say that a head naming a task's shown name counts as naming it.
- **The test that goes red first:** `PersonTest` (or the poll test that writes person files): a person's line
  `Agent Pi: the Build is green` in a room whose member `@pi:x` shows as `Agent Pi` gives `to: ["@pi:x"]`; today
  `[]`. Beside it: a name inside a word (`Agent Pine`) names nobody, a name after the colon names nobody, and a
  member whose shown name is not `Agent <Word>` is never named by the head.

## Acceptance

- The new test fails against the old code and passes against the new one.
- The unit suite passes.
