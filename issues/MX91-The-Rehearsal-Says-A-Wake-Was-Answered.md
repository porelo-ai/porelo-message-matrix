# MX91 — The Rehearsal Says A Wake Was Answered

**Status:** Implementing; blocked by [MX89](README.md#mx89).
**Size:** XS - one step of the rehearsal client: wait for an answer, then report it; no change to the move check.

**What must be true.** The check of an agent's move asks whoever posted the waking message to say they read the
task's answer in the room. In a rehearsal that is said by the run itself, and only after it has seen the answer.

## Plan

- **A `wait-answer <account> <minutes>` step of `RehearsalClient`**: after the waking line is posted, it syncs until
  a message from that task's account follows the line, then exits 0 naming the answer's event; at the limit it exits
  non-zero, naming the line it waited on.
- **The rehearsal passes the move check its "answered" flag only on that exit 0**, never by default and never on a
  timeout.
- **The test that goes red first:** against `FakeSocketHomeserver`, a room with the waking line and no answer makes
  the step exit non-zero at its limit; an answer from another account does not count; an answer from the task's
  account after the line gives exit 0.

## Acceptance

- On a local VM host: the move check's conversation line is green only when the task answered, and red when the
  task was stopped before the waking line.
