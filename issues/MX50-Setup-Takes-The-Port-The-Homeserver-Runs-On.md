# MX50 — Setup Takes The Port The Homeserver Runs On

**Status:** Analyzed.
**Size:** S - `LocalHomeserver.port` and the setup path's start, with their tests; no contract change.

**What must be true.** A first `setup` on an account whose homeserver already runs answers within seconds. It does
not wait three minutes on a port nothing listens on.

## Plan

Seen in sprint 6i (28 Oct): `homeserver-start.sh` had started the unit before any `homeserver.conf` existed, so the
homeserver listened on the unit's default 8008. The first `porelo talk join sprint5 michi` then hung with no output.
`LocalHomeserver.port` found no port in the file, and `freePort` skipped 8008 because the account's own homeserver
held it. So it wrote 8009, and `setup` waited `START_TIMEOUT` (180 s) on 8009 while the unit stayed on 8008. A
`systemctl --user restart porelo-matrix-homeserver` fixed it.

- When `port` writes a new port while the account's unit is active, `setup` restarts the unit (as `publish` does) so
  it listens where the file says, then waits as today.
- Red first: `LocalHomeserverTest` with a stand-in `Unit` that is active and a port taken: `setup` restarts it. Today
  it only starts it, which does nothing for an active unit.

## Acceptance

- With the unit started before `homeserver.conf` exists, the first `setup` answers, and the homeserver listens on the
  port the file names.
