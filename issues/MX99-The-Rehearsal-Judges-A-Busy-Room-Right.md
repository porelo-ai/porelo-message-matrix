# MX99 — The Rehearsal Judges A Busy Room Right

```json
{
  "$schema": "issue-1.json",
  "id": "MX99",
  "title": "The Rehearsal Judges A Busy Room Right",
  "state": "Implementing",
  "size": "M",
  "priority": "Medium",
  "rank": 2,
  "blockedBy": [],
  "covers": "The room rehearsal judges a room with history, other persons, retired tasks and other logins right, and leaves no session or chat open"
}
```

**Why this size.** the rehearsal client and program in `src/test` and their tests; nothing that ships.

**What must be true.** The room rehearsal says `OK` only where the room behaved, and `FAIL` only where it did not, also
in a room with history, other persons, retired tasks and other logins on the same host, and it never leaves the test
person's session or a chat open behind it.

## Why

A reading of [MX89](README.md#mx89) to [MX91](README.md#mx91) by another model (gemini-4-argon-high) found ways the
rehearsal can misjudge a real host. The runs on the foo-org host held, in a young room with one person and three tasks;
a busier room would not.

## Plan

- **A session ends however a run ends**: `close` clears and restores the thread's interrupt around the logout, so an
  interrupted wait still logs out; at the end of the input with a session still open, the run logs out and fails if
  that fails. An interrupted sleep in `RoomRehearsal` is a `Failure`, not an unchecked exception.
- **Only this login's helpers pass**: `@porelo`, and `@porelo.<login>` and `@porelo-relay-….<login>` of the person's
  own login; another login's helper in the room is a stranger.
- **The person joins first**: `RoomRehearsal` joins the room after signing in, so a room the host only invited the
  person to is rehearsed, not failed.
- **A pill names a task by itself**: a scenario with a pill and no head, so a pill that is dropped wakes every task and
  fails; the fake room reads mentions.
- **Reading reaches back to the line**: `messages` asks only room messages and pages back until the line waited on (or
  the room's start for the order check), so 200 later events never hide it; two lines with one id are out of order.
- **A busy room passes**: senders before the rehearsal's first line are not judged; signed task lines and a relay's
  answer are no strangers; further persons can be named with `--person`.
- **`read` answers one line per step**, its messages in an array, as every other step does.
- **The same failure stops the run** when only the event's id differs; a line for every task waits once, not once per
  task.
- **Arguments**: a task named twice, by name, account or display, is refused; tasks compare by value.
- **Tests that stayed green when it broke**: `RoomRehearsal.run` and its argument parsing over the scripted gate,
  including the exit code of a failed run; `join`, `direct`, `members` and `read` of the client; the direct chat left
  when its step fails or throws.

## Acceptance

- Each finding has a test that fails without its fix, and a rehearsal on a host with a second person and a retired
  task in the room holds every step.
