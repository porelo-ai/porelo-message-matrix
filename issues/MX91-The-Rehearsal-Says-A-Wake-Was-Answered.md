# MX91 — The Rehearsal Says A Wake Was Answered

```json
{
  "$schema": "issue-1.json",
  "id": "MX91",
  "title": "The Rehearsal Says A Wake Was Answered",
  "state": "Review",
  "size": "XS",
  "priority": "Medium",
  "rank": 4,
  "blockedBy": ["MX89"],
  "covers": "The rehearsal tells the move check a wake was answered only after it saw the task's answer in the room"
}
```

**Why this size.** one step of the rehearsal client: wait for an answer, then report it; no change to the move check.

**What must be true.** The check of an agent's move asks whoever posted the waking message to say they read the
task's answer in the room. In a rehearsal that is said by the run itself, and only after it has seen the answer.

## Plan

- **A `wait-answer <room> <account> <seconds>` step of `RehearsalClient`** (at most 3600 s): after the waking line is
  posted, it syncs until a message from that task's account follows the line, then exits 0 naming the answer's event; at
  the limit it exits non-zero, naming the line it waited on.
- **The rehearsal passes the move check its "answered" flag only on that exit 0**, never by default and never on a
  timeout.
- **The test that goes red first:** `RehearsalClientTest`, against a scripted gate, a room with the waking line and no
  answer makes the step exit non-zero at its limit; an answer from another account does not count; an answer from the
  task's account after the line gives exit 0.

## Acceptance

- On a local VM host: the move check's conversation line is green only when the task answered, and red when the
  task was stopped before the waking line.

## Measured

- On the foo-org host, 2026-11-09: `wait-answer` on a line for `pi` ended 0 after 12 s, naming pi's answer; a run whose
  line no task answered ended 75 at its limit, naming the line it waited on.
- Not run: a task stopped before the waking line. Stopping a task on that shared host is not the rehearsal's to do; the
  red run without an answer exercises the same exit.
