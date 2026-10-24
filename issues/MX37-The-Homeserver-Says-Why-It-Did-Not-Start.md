# MX37 — The Homeserver Says Why It Did Not Start

**Status:** Implementing.
**Size:** XS - the start's failure path, the unit's log and state, and one optional field in `describe`.

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

- On the timeout after a start of the unit, `LocalHomeserver.awaitUp` reads the last 200 lines of the unit's journal
  (`Unit.recentLog`; a read that fails answers none) and prints last on stderr, alone on its line: the last `Error: `
  line, else the last `ERROR` line, else `the homeserver's unit is <state>` (`Unit.state`); ANSI codes and control
  characters stripped; at most 300 characters. A line holding a secret the transport knows (the registration token,
  a `PORELO_MATRIX_*` token, password or secret, the password `Keep.retake` makes), raw or stripped, is never printed;
  the line then says `the homeserver's last error was withheld: it held a secret`. The timeout sentence stays first.
- `describe` gains `"waitsOn": "the room's homeserver"`, optional, one line, at most 80 characters, in
  `doc/transport.md`.
- Red first: a test where the unit's log ends with the `CreateIOUring` line after an `Error: ` line, and one where
  a line holds the registration token: today neither cause reaches stderr.
- Files: `LocalHomeserver.java`, `Unit.java`, `PodmanUnit.java`, `Keep.java`, `Main.java`, `Describe.java`, their tests
  and the fake units, `doc/transport.md`, `CHANGELOG.md`.

## Acceptance

- A forced failed start on a test VM ends `setup` with the `Error: ` line last on stderr; a token in the log never
  reaches stderr, seen to fail without the filter.
