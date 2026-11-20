# MX116 — Each Verb Only Maps And A Service Does The Work

```json
{
  "$schema": "issue-1.json",
  "id": "MX116",
  "title": "Each Verb Only Maps And A Service Does The Work",
  "state": "Accepted",
  "size": "L",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "Every command line verb, the room commands and the gate's request handler only check their input, map it to a record, call one service method and print its answer; the use cases move into services tested without the command line"
}
```

**Why this size.** Every verb of the transport moves, about 13,000 lines of main code, with no change to the contract
in `doc/transport.md`; it splits by verb group into steps that each pass the whole suite.

**What must be true.** An entry point of the transport - a verb of the command line, a `room` command, the gate's
handler of one request - checks its input, maps it to a record, calls one service method and maps the answer back to
stdout and an exit code. The use case - locks, positions, the homeserver's start, the room checks, what is made,
kept or removed - lives in a service that knows nothing of arguments, stdin or exit codes and is tested without them.

## Why

Read today: `Send.run` parses its arguments, reads the files, checks the size limit, builds the event and sends it;
`Poll.run` parses its arguments and then takes the state directory's locks and runs the poll; `Room.run` checks the
environment, asks the vault, takes the position's lock and reads or posts; `Lifecycle.run` parses options and settings
and dispatches to private use cases in one class of 2,025 lines; `KeepRoom.run` mixes its checks with the
administrator's start. `Main` already only dispatches, and the gate's rules are already apart in `GateRules`. Of 92
test classes, 24 reach a use case only through a verb's `run`.

## Plan

- Per verb group, in this order, each step its own commit with the suite green: `send`, `poll`, `receipt`, `read`,
  `rename`; the `room` commands; `keep-room`, `drop-room`, `expel`; `backup`, `restore`; the lifecycle verbs
  (`setup`, `enroll`, `retire`, `join`, `part`, `clear`, `members`), split out of `Lifecycle` into one service each;
  the gate's request handling last.
- Each verb gets a request record and an answer record; its `run` becomes parse, map, call, print. Failures stay
  `Failure` with the same exit codes, so the contract does not move.
- A service takes its collaborators (the homeserver client, the unit, the clock, the state directory) by
  constructor, so its test hands in stand-ins; the tests that reach a use case through `run` move to the service,
  and each verb keeps one test of its mapping and usage line.
- Red first, per step: the service's test, written against the record before the code moves.

## Acceptance

- No verb's `run` holds more than parsing, mapping, one service call and printing, read verb by verb.
- The whole suite and the integration tests pass after every step; `doc/transport.md` and `describe` are unchanged.
