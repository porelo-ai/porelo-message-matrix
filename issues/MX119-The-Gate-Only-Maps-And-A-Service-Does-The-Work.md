# MX119 — The Gate Only Maps And A Service Does The Work

```json
{
  "$schema": "issue-1.json",
  "id": "MX119",
  "title": "The Gate Only Maps And A Service Does The Work",
  "state": "Analyzed",
  "size": "M",
  "priority": "High",
  "rank": 4,
  "blockedBy": ["MX116"],
  "covers": "The gate's handler of one request only reads it, maps it to a record, calls one service method and writes the HTTP answer; sign-in, registration, SSO and the alias checks move into a gate service; the last of four parts"
}
```

**Why this size.** One handler of about 600 lines in `Gate`, with `GateHomeserver`, `GateLoginTokens` and `GatePreparation` as collaborators that exist; its rules already stand apart in `GateRules`.

**What must be true.** The gate reads one HTTP request from its socket, maps it to a record, calls one gate service method and writes the answer. Who may sign in, register or open a room, and what is forwarded to the homeserver, lives in that service, tested without a socket.

## Why

Read in the code: `Gate.serve` parses the request and, in the same class, signs in, registers, runs SSO, checks room aliases and forwards; `GateRules` already holds the pure rules.

## Plan

- **Requests, in this order:** the alias checks, availability, registration, sign-in, SSO, then the forward.
- `gate --prepare` (`GatePreparation`) already runs apart and moves only if it reads the environment itself.
- **The shape, as for every part of this move:** each verb gets a request record and an answer record; its entry
  point becomes parse, map, one service call, print. The service takes its collaborators by constructor - the
  homeserver client (`MatrixClient`, pointed at `FakeHomeserver` in a unit test as today, so no new interface), the
  `Unit`, the clock, the state directory - and reads no environment, stdin or arguments and writes no stdout. It fails
  with `Failure`, whose kind is the use case's; only the entry point turns it into an exit code or an HTTP answer.
- **Tests:** the tests that reach a use case through `Main.run` move to the service; each entry point keeps one test of
  its mapping, usage line and exit codes; the integration tests stay as they are and prove the contract did not move.
- **Red first, per verb:** the service's test against its record, before the code moves.

## Acceptance

- `Gate.serve` holds no more than reading the request, mapping, one service call and writing the answer.
- The test over the service classes' code covers the gate service too.
- The whole suite and the integration tests pass after every verb's step; `doc/transport.md` and `describe` are
  unchanged.
