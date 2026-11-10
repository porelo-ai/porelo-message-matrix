# MX102 — The Busy Room Rehearsal Is One Call

```json
{
  "$schema": "issue-1.json",
  "id": "MX102",
  "title": "The Busy Room Rehearsal Is One Call",
  "state": "Implementing",
  "size": "S",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "build/rehearse-busy-room.sh <login> <host> <project> runs MX99's rehearsal against a host as one call: it finds the project's room and its running tasks itself and keeps the rehearsal's exit"
}
```

**Why this size.** a `--project` mode of the rehearsal that finds the room by its alias and the running tasks among its
members, a script around the forward and the call, and their tests.
**How it stands.** Implementing (2026-11-10). Filed and started on the coordinator's word (16:39Z): the operator's
automated rental run takes MX99's rehearsal as a step of its own, before the operator tests live; proven first on Agent
Machines' local VM proof. Before [MX100](README.md#mx100).

**What must be true.** One command, `build/rehearse-busy-room.sh <login> <host> <project>`, rehearses the project's
room on a host as the person `<login>`, with nothing else given: it ends 0 only when every scenario held, 65 when one
did not, 75 when the host or its homeserver could not be reached, 64 on a wrong call, and leaves no forward, session or
direct chat behind.

## Why

[MX99](README.md#mx99)'s rehearsal takes the room's id and each task's name, account and display. A run that brings a
host up from nothing knows none of them before the tasks stand, so a person had to look them up and type them in: a
hand-run, not a step.

## Plan

- **The rehearsal finds them** with `--project P` in place of `--room` and `--task`: after signing in, it asks the
  homeserver's directory for `#porelo-P:<server>` and reads the room's joined members. A task is a member
  `@porelo-P-<task>.<login>:<server>` of the signed-in person's login, as `members` reads one; its display is the
  room's. A stopped task has left the room, so it is not among them. Fewer than two is refused (65), naming what was
  found. The run prints the room and the tasks it found before the scenarios.
- **The script** checks its three arguments, builds the test classpath, opens the ssh forward of the host's
  `/run/porelo-matrix/client.sock` as `<login>`, waits for it, runs the rehearsal with `--talk-log ssh <login>@<host>
  porelo talk log`, closes the forward and ends with the rehearsal's exit. ssh's options come from the caller's
  `ssh` (its config and agent), so the run's pinned host key is used.
- **The test that goes red first:** the rehearsal's tests for the project mode (room by alias, tasks of this login
  only, a stopped task left out, fewer than two refused, `--project` beside `--room` or `--task` refused) and the
  script's (a wrong argument refused before anything runs, a forward that does not come up ends 75).
