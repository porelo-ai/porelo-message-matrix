# MX47 — A Central Setup Joins When Already Invited

**Status:** Estimated by the operator on 2026-10-28.
**Size:** XS - one branch in `Lifecycle`'s central `setup` and its test; no contract change.

**What must be true.** A machine that `admit` invited into the room before it registered joins the room at its first
task start, not only at the second.

## Plan

Seen in sprint 6i (28 Oct): the host's first task start registered `@porelo.sprint5` and answered `admitted: false`.
Only the second start joined. `Lifecycle`'s central `setup` returns before joining when the machine has just
registered: "A machine just registered cannot have been invited yet". With `admit`, which invites before the account
exists, that is no longer true.

- After registering, try `joinCentralRoom` once. If it finds no invitation, answer `admitted: false` as today, with
  the generated secret kept.
- Red first: `CentralIT` admits a machine on a local homeserver published for another machine, runs `setup` once, and
  expects `admitted: true`.

## Acceptance

- One `setup` after `admit` answers `admitted: true` with `conversation` and `secrets`.
- Without an invitation, the first `setup` still answers `admitted: false`, and its secret is kept for the next run.
