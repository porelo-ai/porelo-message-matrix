# MX117 — The Host Commands Only Map And A Service Does The Work

```json
{
  "$schema": "issue-1.json",
  "id": "MX117",
  "title": "The Host Commands Only Map And A Service Does The Work",
  "state": "Estimated",
  "size": "M",
  "priority": "High",
  "rank": 2,
  "blockedBy": ["MX116"],
  "covers": "The host's commands (`keep-room`, `drop-room`, `expel`, `backup`, `restore`, `homeserver-token`) only check their input, map it to a record, call one service method and print its answer; the second of four parts"
}
```

**Why this size.** Six commands, about 1,600 lines in `KeepRoom`, `Expel`, `Keep` and `HomeserverToken`, in the shape the first part set.

**What must be true.** A command the host's helper runs checks its input - arguments, the names on stdin, the administrator's token - maps it to a record, calls one service method and prints the answer. Making, keeping and dropping a room, expelling a login and backing up or restoring the conversation live in services tested without the command line.

## Why

Read in the code: `KeepRoom.run` mixes its argument and token checks with starting the administrator behind the gate and keeping the room; `Expel.run` and `Keep.run` take their locks and do their work in the same method that reads the arguments.

## Plan

- **Commands, in this order:** `homeserver-token`, `expel`, `drop-room`, `keep-room`, `backup`, `restore`.
- **The shape, as for every part of this move:** each verb gets a request record and an answer record; its entry
  point becomes parse, map, one service call, print. The service takes its collaborators by constructor - the
  homeserver client (`MatrixClient`, pointed at `FakeHomeserver` in a unit test as today, so no new interface), the
  `Unit`, the clock, the state directory - and reads no environment, stdin or arguments and writes no stdout. It fails
  with `Failure`, whose kind is the use case's; only the entry point turns it into an exit code or an HTTP answer.
- **Tests:** the tests that reach a use case through `Main.run` move to the service; each entry point keeps one test of
  its mapping, usage line and exit codes; the integration tests stay as they are and prove the contract did not move.
- **Red first, per verb:** the service's test against its record, before the code moves.

## Acceptance

- No host command's `run` holds more than parsing, mapping, one service call and printing.
- The test over the service classes' code covers this part's services too.
- The whole suite and the integration tests pass after every verb's step; `doc/transport.md` and `describe` are
  unchanged.
