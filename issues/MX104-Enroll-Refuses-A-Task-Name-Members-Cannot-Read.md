# MX104 — Enroll Refuses A Task Name Members Cannot Read

```json
{
  "$schema": "issue-1.json",
  "id": "MX104",
  "title": "Enroll Refuses A Task Name Members Cannot Read",
  "state": "Backlog",
  "size": "XS",
  "priority": "High",
  "rank": 2,
  "blockedBy": [],
  "covers": "`enroll --project P --task T` refuses (78) a T that `members` would not read back as a task of P, so a wrong name fails at the start, not later in the room"
}
```

**Why this size.** one check in `Lifecycle.enroll` for both homeservers, its tests and one sentence of the contract.
**How it stands.** Found in a rented-host run: tasks were enrolled as `porelo-<P>_<task>`, which `members` reads as a
person and the room rehearsal does not find; nothing refused the name. Agreed in the channel; the porelo side sends
the contract's name.

**What must be true.** A task Porelo enrolls is always one that `members --project P` lists as a task of P. Any other
name is refused with 78, naming the expected form, and no account is made.

## Plan

- **`Lifecycle.enroll` and `enrollCentral`** check `--task` before anything is made: it starts `porelo-<P>-`, the rest is
  non-empty and the whole matches the name `members` takes as a task (`Members.kind`'s rule, one method both use, so
  the two cannot drift). Otherwise `Failure(Exit.CONFIG, ...)`.
- **`retire`** keeps accepting any name, so an account enrolled under an old spelling can still be retired.
- **`doc/transport.md`:** the `enroll` row says a task name outside `porelo-P-<task>` is refused (78).
- **The test that goes red first:** `LifecycleTest` enrolls `porelo-foo_x` and `porelo-bar-x` into project `foo` and
  expects 78 with no registration request on the fake homeserver; `porelo-foo-x` still enrolls. Harm prevented: a task
  enrolled under a name the room's readers do not take as a task gets no line meant for it.

## Acceptance

- `enroll --project foo --task porelo-foo_x` ends 78 and the homeserver holds no new account.
- `enroll --project foo --task porelo-foo-x` enrolls as before, and `members --project foo` lists it as a task.
- The test is seen red with the check removed.
