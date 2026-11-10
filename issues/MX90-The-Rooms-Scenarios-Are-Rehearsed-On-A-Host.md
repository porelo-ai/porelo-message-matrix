# MX90 — The Room's Scenarios Are Rehearsed On A Host

```json
{
  "$schema": "issue-1.json",
  "id": "MX90",
  "title": "The Room's Scenarios Are Rehearsed On A Host",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 3,
  "blockedBy": ["MX89"],
  "covers": "A rehearsal proves on a host that a person's line reaches exactly the tasks meant (a head, `All agents:`, `@room`, nobody, a direct chat) and that the task's answer comes back"
}
```

**Why this size.** one step list for the rehearsal and one try on a local VM host; no code that ships.

**What must be true.** A rehearsal on a host proves, unattended, that what a person writes in the project's room
reaches exactly the tasks it is meant for, and that a task's answer comes back to the person.

## Plan

- **A program in `src/test`, `RoomRehearsal`**, run with the test classpath as `RoomRehearsal --socket <path> --room
  <!room-id> [--answer-within <s>] [--quiet-for <s>] --task <task>=<@account>=<display> ... --talk-log <command...>`. It
  signs the test person in with `RehearsalClient`, runs the steps, and logs out. The `talk log` command runs on the host
  as the person (`ssh <person>@<host> porelo talk log`), the task's name appended. Tasks run on an API-key provider, so
  no agent's browser sign-in is needed. It ships in no package.
- **What porelo records, and what it does not:** every task of the project gets each of a person's room lines; its `talk
  log` shows the line by the file the transport wrote for it, `<time>--matrix-person-<name UUID of the event id>.json`
  (seen on a host) with the event `delivered`. Whom a line is meant for and whether it woke a task is in no command's
  output, so those are judged by who answers in the room. A line wakes a task when it names that task, is marked for
  all, or names nobody. So a line for all that is not recognized as such still wakes every task, as a line naming nobody
  does: the rehearsal cannot tell the two apart until `talk log` prints whom a line was meant for.
- **Each line asks for an answer with a nonce of its own** (`answer with <nonce>`), and only a task's line carrying it
  counts, so a late answer to an earlier step is not taken for this one. A task's name goes onto the host's command
  line, so it is refused unless it matches `[a-z0-9][a-z0-9._-]*`; `talk log` runs at most 60 s and is read whole.
- **The steps:**
  - `Agent <A>: …` typed with no pill, and once with a pill: every task's record has it delivered; A answers; no other
    task answers within the quiet time.
  - For two tasks whose displays share a start (`Agent Pi`, `Agent Pi-Two`), a head naming each: only the task with
    the whole name answers. Without such a pair the step is reported `SKIP`.
  - `All agents: …`, a line mentioning `@room`, and a line naming nobody: every task answers.
  - An unencrypted direct chat with A: A answers there and its record has it; after the quiet time no other task's
    record has it. The person leaves the chat after the step, whatever came of it, so no later run's answer goes into
    it ([MX98](README.md#mx98)); `joined` and `leave <room>` let chats an earlier run left go.
  - Reading the room: two lines read back in the order sent; every sender is a task or the person, every member also
    may be one of the room's helpers on the person's server (`@porelo`, `@porelo.<login>`, `@porelo-relay-…`), which
    made the room, invite to it and read it, as seen on a host; anyone else is a stranger.
- **Output:** one line per step, `OK` or `FAIL`, with what was asked and what came back; the run stops when the same
  failure comes twice in a row. Exit 0 only when every step held. About 10 minutes, mostly waiting for answers.
- **The tests:** `RoomRehearsalTest`, against a room and records in memory, its answers coming a few reads late: a room
  that behaves holds every step; a named line other tasks answer, a named task that stays silent, an answer without the
  line's nonce, a line a task's record lacks, a task silent on a line for all, a direct line in another task's record or
  missing from its own, a silent stranger among the members, a helper's line and lines read out of order each fail, the
  room's helpers among the members hold; a missing pair of displays is named `SKIP`; a held row does not count as
  delivered; the line's id is the one porelo gives it; a task name that is no name is refused; a `talk log` that does
  not end is stopped, and one of 5 MB is read whole.

## Acceptance

- On a local VM host with at least three tasks of one project, every step is `OK`, and a task stopped before the run
  turns its steps `FAIL`, naming the task.
- One run on a rented host, on the operator's word.

## Measured

- On the foo-org host with three tasks of one project (`pi`, `pi-two`, `scout`), last pass 2026-11-10 00:01Z to 00:12Z:
  all 11 steps `OK`, exit 0.
- Earlier passes found two faults, both fixed red first: the room's helpers counted as strangers (they made the room,
  invite to it and read it), and the direct chat got no answer because a task answers into the oldest direct chat it
  shares with the person ([MX98](README.md#mx98)); the rehearsal now leaves its chat after the step, and before the last
  pass the person left the three chats earlier runs had left open.
- Not run: a task stopped before the run. Stopping a task on that shared host is not the rehearsal's to do;
  `RoomRehearsalTest` covers a named task that stays silent.
- A reading by gemini-4-argon-high found ten ways the rehearsal can misjudge a busier room; two are fixed, the rest are
  [MX99](README.md#mx99).
