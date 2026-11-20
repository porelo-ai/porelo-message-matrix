# MX118 — The Lifecycle Verbs Only Map And A Service Does The Work

```json
{
  "$schema": "issue-1.json",
  "id": "MX118",
  "title": "The Lifecycle Verbs Only Map And A Service Does The Work",
  "state": "Analyzed",
  "size": "L",
  "priority": "High",
  "rank": 3,
  "blockedBy": ["MX116"],
  "covers": "The lifecycle verbs (`setup`, `enroll`, `retire`, `join`, `part`, `clear`, `members`, `settings`) only check their input and settings, map them to a record, call one service method and print its answer; `Lifecycle` splits into one service each; the third of four parts"
}
```

**Why this size.** Eight verbs out of one class of 2,025 lines, about 3,400 lines with `LocalHomeserver`, `Accounts`, `Members` and `SettingsCheck`, the verbs Porelo runs most and the contract's largest part.

**What must be true.** A lifecycle verb checks its options and the settings and secrets on stdin, maps them to a record, calls one service method and prints the answer. Starting the homeserver, making accounts and rooms, the room checks and clearing live in one service per verb, tested without the command line.

## Why

Read in the code: `Lifecycle.run` parses the options, reads the settings, checks loopback-only and then dispatches to private use cases - `setup`, `setupCentral`, `enroll`, `join` and the others - all in one class, so a test of a use case goes through the verb.

## Plan

- **Verbs, in this order:** `settings`, `members`, `retire`, `part`, `enroll`, `join`, `setup` (the account's own
  homeserver, then a central one), `clear` (a project, then the whole homeserver).
- What several verbs share - the administrator's session, the room check, the account's registration - becomes
  collaborators of their own, so `Lifecycle` is left with the dispatch and the option parsing.
- **The shape, as for every part of this move:** each verb gets a request record and an answer record; its entry
  point becomes parse, map, one service call, print. The service takes its collaborators by constructor - the
  homeserver client (`MatrixClient`, pointed at `FakeHomeserver` in a unit test as today, so no new interface), the
  `Unit`, the clock, the state directory - and reads no environment, stdin or arguments and writes no stdout. It fails
  with `Failure`, whose kind is the use case's; only the entry point turns it into an exit code or an HTTP answer.
- **Tests:** the tests that reach a use case through `Main.run` move to the service; each entry point keeps one test of
  its mapping, usage line and exit codes; the integration tests stay as they are and prove the contract did not move.
- **Red first, per verb:** the service's test against its record, before the code moves.

## Acceptance

- No lifecycle verb's dispatch holds more than parsing, mapping, one service call and printing; `Lifecycle` holds no
  use case.
- The test over the service classes' code covers this part's services too.
- The whole suite and the integration tests pass after every verb's step; `doc/transport.md` and `describe` are
  unchanged.
