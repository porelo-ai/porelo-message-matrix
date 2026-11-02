# MX74 — Small Refusals Are Held By Tests

**Status:** Backlog.
**Size:** S - single cases in test classes that exist.

**What must be true.** The smaller refusals, each cheap to hold, fail a unit test if removed.

## Plan

- `GateFilesTest` or `ExpelTest`: a `homeserver.conf` without the gate's line is not gate mode, also for `expel`.
- `LoginTest`: `.alice` and `:alice` are no person's account.
- `PodmanUnitTest`: a launcher that is not root-owned, or that is group-writable, is refused.
- `SettingsCheckTest`: a token file readable by group or others warns, each alone.
- `LifecycleTest`: `clear` without `--project` and with a wrong form; `--display` on a verb other than `enroll` and `join`.
- `RoomTest` and `SendTest`: an option with no value after it, and an id one byte over its limit.

## Acceptance

- Measured: PIT kills the mutants of these checks.
- Seen to fail: each case.
- Dropped rather than forced: a case whose test would need root or a second account is named here and left out.
