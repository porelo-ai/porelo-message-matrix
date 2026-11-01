# MX62 — Expel Reaches The Homeserver Behind Its Gate

**Status:** Implementing.
**Size:** S - one verb and its tests, no contract change.
**Severity:** High - on a host behind the gate, `expel` deactivates nobody, so a purged person's accounts stay
able to sign in until someone takes them out by hand.

**What must be true.** On a host whose homeserver runs behind its gate, the host's helper runs `expel <login>` as
the homeserver's account with the gate's administrator token on stdin, and the login's accounts are deactivated
and recorded as on a homeserver without a gate.

## Why

Today `expel` reaches the homeserver through `LocalHomeserver.ensure`, at `http://127.0.0.1:<port>`. In gate
mode the homeserver listens only on its private Unix socket, and the host port forwards to container port 8009,
where nothing listens. Measured in a test account with the packaged transport, after
`gate --prepare` had answered `"administrator":true`: `expel bob` exits 75 after 180 s with "the homeserver at
http://127.0.0.1:8008 did not answer within 180 s", and deactivates nothing.

## Plan

- `expel`'s stdin and its answer stay as `doc/homeserver.md`
  states them.
- In gate mode (`GateFiles.enabled`), `Expel.run` starts the unit as today but takes its client from
  `GateHomeserver`, which checks the private socket's ownership before it sends the token. Without the gate it
  keeps `LocalHomeserver.ensure`.
- The test that goes red first is a `GateIT` case. After `gate --prepare` registers the administrator, it runs
  `expel` for a login that has an account and sees that account deactivated and the login recorded.

## Acceptance

- Behind the gate, `expel <login>` deactivates that login's accounts, records the login, and exits 0. Run again,
  it answers `known: true` with nothing left to expel.
- Seen to fail first: today's `expel` exits 75 behind the gate.
- Without the gate, `ExpelTest` and the local homeserver's tests stay green.
