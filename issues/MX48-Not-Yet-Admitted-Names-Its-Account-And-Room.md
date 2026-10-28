# MX48 — Not Yet Admitted Names Its Account And Room

**Status:** Analyzed.
**Size:** XS - two fields in one answer, the doc's table and a test; porelo's contract already names them.

**What must be true.** When a machine is not in the project's room yet, the person reads which account to let in
and into which room.

## Plan

Seen in sprint 6i (28 Oct): porelo printed "this machine is not in the project's matrix conversation yet: a person
there lets its account in". It named no account and no room, since this transport's answer had neither. porelo's
contract (`doc/transports.md`, the `setup` row) has a setup on a shared server answer `machine`, `address`, `room`
and `admitted`. `Lifecycle`'s central `setup` answers only `machine` with `admitted: false`.

- Answer `address` (`@porelo.<M>:<server>`) and `room` (the configured room) there too. `doc/transport.md`'s `setup`
  row says so.
- Red first: `LifecycleTest`/`CentralIT` expects both fields with `admitted: false`.

## Acceptance

- The answer with `admitted: false` holds `address` and `room`, and porelo's message names both.
