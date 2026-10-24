# MX37 — The Homeserver Says Why It Did Not Start

**Status:** Idea.
**Size:** XS - one class and its test, and one optional field in `describe`.

**What must be true.** When `setup` gives up waiting for the local homeserver, a person reads why in Porelo's
start, from the homeserver's own words, never a secret; and Porelo can say what a start waits on. This
repository's part of porelo's [B268](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b268).

## Why

- Today `setup` waits up to 180 s (`LocalHomeserver.awaitUp`) and ends with its own sentence; nothing is read from
  the unit. Porelo shows the last non-empty line of the transport's stderr, at most 300 characters, control
  characters removed, as the cause.

## Measured

- On a test VM against the pinned Tuwunel 1.9.4, five starts with a random registration token as a marker (a normal
  start; too few processes; a database path that cannot be made; a malformed setting; debug logging): no log line
  held the token or named a token, password or secret; a malformed setting is named by its key, not its value.
- The last line is not the cause: on two of three failures it is
  `CreateIOUring failed: Function not implemented (errno=38)`, printed at exit. The cause is the line starting
  `Error: `, also logged as `ERROR tuwunel: Critical error starting server: ...`. Lines carry ANSI color codes.

## Plan

- On the timeout, `setup` reads the unit's container log (the journal of `porelo-matrix-homeserver.service`, or
  `podman logs` of its container) and prints last on stderr: the last `Error: ` line, else the last `ERROR` line,
  else the unit's state ("exited 1"); ANSI codes and control characters stripped; at most 300 characters. A line
  holding a secret the transport knows (the registration token, the administrator's password) is never printed;
  then it says the cause was withheld.
- `describe` gains `"waitsOn": "the room's homeserver"`, optional, one line, at most 80 characters, in
  `doc/transport.md`.
- Red first: a test where the unit's log ends with the `CreateIOUring` line after an `Error: ` line, and one where
  a line holds the registration token: today neither cause reaches stderr.
- Files: `LocalHomeserver.java`, `Describe.java`, `doc/transport.md`, `CHANGELOG.md`, their tests.

## Acceptance

- A forced failed start on a test VM ends `setup` with the `Error: ` line last on stderr; a token in the log never
  reaches stderr, seen to fail without the filter.
