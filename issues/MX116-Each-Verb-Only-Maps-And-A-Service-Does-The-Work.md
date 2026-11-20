# MX116 — Each Verb Only Maps And A Service Does The Work

```json
{
  "$schema": "issue-1.json",
  "id": "MX116",
  "title": "Each Verb Only Maps And A Service Does The Work",
  "state": "Analyzed",
  "size": "L",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "The message verbs (`send`, `poll`, `receipt`, `read`, `rename`, `check`) and the `room` commands only check their input, map it to a record, call one service method and print its answer; their use cases move into services tested without the command line; the first of four parts"
}
```

**Why this size.** Six verbs and three `room` commands, about 2,200 lines in `Send`, `Poll`, `Receipt`, `Read`, `Rename`, `Check`, `Room` and `Direct`, with their tests; the first part sets the shape the other three follow, which costs the most thought.

**What must be true.** An entry point of the transport - here a message verb or a `room` command - checks its input, maps it to a record, calls one service method and maps the answer back to stdout and an exit code. The use case lives in a service that knows nothing of arguments, stdin, the environment or exit codes, and is tested without them.

## Why

Read in the code: `Send.run` parses its arguments, reads the files, checks the size limit, builds the event and sends it; `Poll.run` parses its arguments, then takes the state directory's locks and runs the poll; `Room.run` checks the environment, asks the vault, takes the position's lock and reads or posts. `Main` already only dispatches. The host commands, the lifecycle verbs and the gate follow in [MX117](README.md#mx117), [MX118](README.md#mx118) and [MX119](README.md#mx119).

## Plan

- **Verbs, in this order:** `check`, `rename`, `read`, `receipt`, `send`, `poll`; then `room read`, `room post`, `room wait`,
  whose vault question becomes a collaborator of its own.
- **The shape, as for every part of this move:** each verb gets a request record and an answer record; its entry
  point becomes parse, map, one service call, print. The service takes its collaborators by constructor - the
  homeserver client (`MatrixClient`, pointed at `FakeHomeserver` in a unit test as today, so no new interface), the
  `Unit`, the clock, the state directory - and reads no environment, stdin or arguments and writes no stdout. It fails
  with `Failure`, whose kind is the use case's; only the entry point turns it into an exit code or an HTTP answer.
- **Tests:** the tests that reach a use case through `Main.run` move to the service; each entry point keeps one test of
  its mapping, usage line and exit codes; the integration tests stay as they are and prove the contract did not move.
- **Red first, per verb:** the service's test against its record, before the code moves.

## Acceptance

- No message verb's `run` and no `room` command holds more than parsing, mapping, one service call and printing, read
  entry point by entry point.
- No service of this part reads the environment, stdin or arguments, or writes stdout, held by a test over the
  service classes' code that the later parts extend.
- The whole suite and the integration tests pass after every verb's step; `doc/transport.md` and `describe` are
  unchanged.

## Open questions

- **Order against the Matrix page's issues** (the overview, opening a room, keeping the conversation): build those
  first, their new verbs already in this shape, and these four parts after; or these first, so they land on
  services? Recommended: those first - they are High and wanted by a story under test, and this moves no behavior.

## To be checked

- Whether `Failure` keeps its `Exit` kinds as the services' error, or a service error of its own maps to them; read in
  `Exit`'s users before the first verb moves.
- The mutation tests (the PIT profile) over the moved services, so a test that only went through `Main.run` is not lost.
