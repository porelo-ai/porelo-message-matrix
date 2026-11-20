# MX116 — Each Verb Only Maps And A Service Does The Work

```json
{
  "$schema": "issue-1.json",
  "id": "MX116",
  "title": "Each Verb Only Maps And A Service Does The Work",
  "state": "Analyzed",
  "size": "XL",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "Every command line verb, the room commands and the gate's request handler only check their input, map it to a record, call one service method and print its answer; the use cases move into services tested without the command line"
}
```

**Why this size.** Every verb of the transport moves, about 13,000 lines of main code and the tests of 19 test classes
that run a verb through `Main.run`, with no change to the contract in `doc/transport.md`. Too much for one build: it
splits into four issues, each of L or smaller, as the plan says.

**What must be true.** An entry point of the transport - a verb of the command line, a `room` command, the gate's
handler of one request - checks its input, maps it to a record, calls one service method and maps the answer back:
to stdout and an exit code, or to an HTTP answer. The use case - locks, positions, the homeserver's start, the room
checks, what is made, kept or removed - lives in a service that reads no argument, stdin or environment and writes no
stdout, and is tested without them. A service fails with `Failure`, whose kind (temporary, refused, configuration,
...) is the use case's; only the entry point turns it into a process's exit code.

## Why

Read in the code: `Send.run` parses its arguments, reads the files, checks the size limit, builds the event and sends
it; `Poll.run` parses its arguments, then takes the state directory's locks and runs the poll; `Room.run` checks the
environment, asks the vault, takes the position's lock and reads or posts; `Lifecycle.run` parses options and
settings and dispatches to private use cases inside one class of 2,025 lines; `KeepRoom.run` mixes its checks with
starting the administrator; `Gate.serve` parses an HTTP request and runs sign-in, registration and SSO in the same
class. `Main` already only dispatches, and the gate's rules stand apart in `GateRules`.

## Plan

- **Split into four issues**, each built and pushed alone, the suite green after every step:
  1. **The message verbs and the room commands (L):** `send`, `poll`, `receipt`, `read`, `rename`, `check`, and
     `room read|post|wait`; `Send`, `Poll`, `Receipt`, `Read`, `Rename`, `Room` and `Direct`.
  2. **The host commands (M):** `keep-room`, `drop-room`, `expel`, `backup`, `restore`, `homeserver-token`;
     `KeepRoom`, `Expel`, `Keep`, `HomeserverToken`.
  3. **The lifecycle verbs (L):** `setup`, `enroll`, `retire`, `join`, `part`, `clear`, `members`, `settings`,
     split out of `Lifecycle` into one service each, with `LocalHomeserver` and `Accounts` as their collaborators.
  4. **The gate (M):** `Gate.serve` keeps reading and writing HTTP; sign-in, registration, SSO and the room rules'
     checks move into a gate service over `GateHomeserver` and `GateLoginTokens`.
- **Per verb:** a request record and an answer record; its `run` becomes parse, map, call, print. The services take
  their collaborators by constructor - the homeserver client (`MatrixClient`, pointed at `FakeHomeserver` in a unit
  test as today, so no new interface), the `Unit`, the clock, the state directory - and never the environment map.
- **Tests:** the tests that reach a use case through `Main.run` move to the service; each verb keeps one test of its
  mapping, usage line and exit codes; the integration tests stay as they are and prove the contract did not move.
- **Red first, per verb:** the service's test against its record, before the code moves.
- **New verbs** of other issues (`overview`, `clear --keep`) are written in this shape from the start.

## Acceptance

- No verb's `run`, `room` command or gate handler holds more than parsing, mapping, one service call and the answer,
  read entry point by entry point.
- No service reads the environment, stdin or arguments, or writes stdout, checked by a test over the service
  classes' code.
- The whole suite and the integration tests pass after every step; `doc/transport.md` and `describe` are unchanged.

## Open questions

- **Order against the Matrix page's issues** (the overview, opening a room, keeping the conversation): build those
  first, their new verbs already in this shape, and this after; or this first, so they land on services? Recommended:
  those first - they are High and wanted by a story under test, and this moves no behavior.

## To be checked

- Whether `Failure` should keep its `Exit` kinds as the services' error, or a service error of its own maps to them;
  read in `Exit`'s users before the first issue is built.
- The mutation tests (the PIT profile) over the moved services, so a test that only went through `Main.run` is not
  lost.
