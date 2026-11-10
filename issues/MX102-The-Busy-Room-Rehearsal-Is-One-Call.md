# MX102 — The Busy Room Rehearsal Is One Call

```json
{
  "$schema": "issue-1.json",
  "id": "MX102",
  "title": "The Busy Room Rehearsal Is One Call",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "build/rehearse-busy-room.sh <login> <host> <project> runs the busy room's rehearsal against a host as one call: it finds the project's room and its running tasks itself and keeps the rehearsal's exit"
}
```

**Why this size.** a `--project` mode of the rehearsal that finds the room by its alias and the running tasks among its
members, a script around the forward and the call, and their tests.
**How it stands.** Review (2026-11-10): built, its tests green; proven next on Agent Machines' local VM proof. Filed
and started on the coordinator's word (16:39Z): the operator's automated rental run takes the rehearsal of
[MX99](README.md#mx99) as a step of its own, before the operator tests live. Before [MX100](README.md#mx100).

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

## Plan as built

- **`RoomRehearsal --project P`** takes the place of `--room` and `--task`; beside either, or with a name outside the
  gate's project rule, it is refused (64). After the sign-in it asks the directory for `#porelo-P:<server>`, the
  person's server (`no room is named …` is 65), reads the room's joined members, and takes as a task each
  `@porelo-P-<task>.<login>:<server>` of the signed-in person: `RoomRehearsal.running`, by name. A display no head can
  name is 65, naming it; fewer than two is 65, naming those found. It prints `ROOM <id> (<alias>)` and one `TASK <name>
  <account> <display>` line each, then joins and runs as before.
- **`build/rehearse-busy-room.sh <login> <host> <project>`** checks the three values as `room-tunnel.sh` does, builds the
  test classpath (`REHEARSE_CLASSPATH` skips that), opens `ssh -N -L <dir>/client.sock:/run/porelo-matrix/client.sock`
  as `<login>` and waits up to 30 s for it (75 when it does not come up), runs the rehearsal with `--talk-log ssh -o
  BatchMode=yes <login>@<host> porelo talk log`, and at its end kills the forward and removes its directory. Its exit
  is the rehearsal's. Named among the files that cannot be Java in `AGENTS.md`, and in the homeserver's document.
- **Tests:** `RoomRehearsalTest` (the project's arguments, the running tasks, too few or unnameable), `RehearsalClientTest`
  (a project's run finds and says its room and tasks; a project with no room is 65 and logs out), and
  `RehearseBusyRoomTest` with stand-ins for `ssh` and `java` (a wrong call runs nothing; the call, its exit and the
  closed forward; a forward that fails or never comes up is 75). The rehearsal's tests were each seen red by a break;
  the script's breaks stopped halfway by the operator's rule of 17:45Z (a test script gets no red-first proof; its
  proof is the VM run, then the rental).
- **Found on the way:** `RehearsalClientTest`'s stand-in gate ended on a client that closed its end first, as an
  interrupted one does, so `anInterruptedRehearsalStopsAndLogsOut` waited out its 30 s for the logout about one run in
  ten. The gate now ends only that request; 25 runs in a row green.
- **The run's step**, `build/rehearse-steps/busy-room`, is written to porelo-host's step hook (Agent Machines, 17:54Z)
  and copied there as it is: it reads the host, the person, the project, the person's agent, the pinned known hosts and
  the hard stop from the environment, takes a worktree of this repository's main, runs the one call with every ssh
  going as the person by the run's agent and key alone, and ends with the rehearsal's exit; 77 (SKIP) with less than
  30 min to the hard stop. Its first dry run found the forward's socket under a long `TMPDIR` too long for ssh; the
  script now keeps it under `$XDG_RUNTIME_DIR` or `/tmp`.

## Read

One reading by gemini-4-argon-high of both commits (its first try ran 15 min with no answer), reds only:

- **Fixed:** a person only invited to the room may not read its members, so the project's run now joins before it
  reads them (the client test checks the order).
- **Fixed:** the rehearsal ends 77 on a refusal (401, 403), which the run's hook reads as SKIP; the step ends 1 then.
- **Left, said here:** on SIGINT or SIGTERM the JVM ends without logging the person out or leaving a direct chat, and
  the forward dies with it. In the run that is the hard stop's kill, and the rental is deleted after it; the rule for
  test scripts (17:45Z) asks no more.
- **Yellows, noted:** two discovered tasks sharing a display end 64, not 65; the step's `timeout` ends 124, not 75; the
  script's login check is wider than `Login.valid`; the ssh wrapper keeps the user's own ssh configuration.
