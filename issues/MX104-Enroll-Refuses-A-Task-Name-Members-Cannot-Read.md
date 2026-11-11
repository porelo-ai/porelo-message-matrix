# MX104 — Enroll Refuses A Task Name Members Cannot Read

```json
{
  "$schema": "issue-1.json",
  "id": "MX104",
  "title": "Enroll Refuses A Task Name Members Cannot Read",
  "state": "Review",
  "size": "XS",
  "priority": "High",
  "rank": 2,
  "blockedBy": [],
  "covers": "`enroll --project P --task T` refuses (78) a T that `members` would not read back as a task of P, so a wrong name fails at the start, not later in the room"
}
```

**Why this size.** one check in `Lifecycle.enroll` for both homeservers, its tests, one sentence of the contract, and
the integration tests' task names moved to the contract's form; at the upper end of XS.
**How it stands.** Review: built. The unit suite and the touched integration tests (LifecycleIT, CentralIT, GateIT,
KeepIT, KeepRoomIT) are green on the development machine. The new tests were seen red before the check, and the
self-left takeover test was seen red with `partedFrom`'s sender check removed. Found in a rented-host run: tasks were
enrolled as `porelo-<P>_<task>`, which `members` reads as a person and the room rehearsal does not find; nothing refused
the name. The porelo side sends the contract's name.

**What must be true.** A task Porelo enrolls is always one that `members --project P` lists as a task of P. Any other
name is refused with 78, naming the expected form, and no account is made.

## Plan

- **`Lifecycle.enroll` and `enrollCentral`** check `--task` before anything is made: it starts `porelo-<P>-`, the rest is
  non-empty and the whole matches the name `members` takes as a task (`Members.kind`'s rule, one method both use, so
  the two cannot drift), and does not end in a dot, which the host's gate refuses before the login. Otherwise
  `Failure(Exit.CONFIG, ...)`; a project whose task prefix would read as a relay's, `relay` or `relay-…`, is told it
  can enroll no task.
- **The tests that enroll** a task named outside the form (`worker`, `erin`, `t`) take `porelo-demo-<task>`; the one
  that showed a person cannot take a retired task's name now shows it for an account that left the room by itself
  (77), which `partedFrom` decides, since join refuses a `porelo` name before it reads the room.
- **`retire`** keeps accepting any name, so an account enrolled under an old spelling can still be retired.
- **`doc/transport.md`:** the `enroll` row says a task name outside `porelo-P-<task>` is refused (78).
- **The test that goes red first:** `LifecycleTest` enrolls `porelo-foo_x` and `porelo-bar-x` into project `foo` and
  expects 78 with no registration request on the fake homeserver; `porelo-foo-x` still enrolls. Harm prevented: a task
  enrolled under a name the room's readers do not take as a task gets no line meant for it.

## Acceptance

- `enroll --project foo --task porelo-foo_x` ends 78 and the homeserver holds no new account.
- `enroll --project foo --task porelo-foo-x` enrolls as before, and `members --project foo` lists it as a task.
- The test is seen red with the check removed.
