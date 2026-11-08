# MX84 — Setup Waits For No Poll It Need Not

**Status:** Review; accepted by the operator, 2026-11-08. Bug, found in a move to a host.
**Size:** S - one method in `Lifecycle` and its tests; no contract another repository reads.

**What must be true.** Several tasks of one project started at once on a central homeserver all get through `setup`.
None ends 75 because the project's relay is being polled, unless setup really has to forget the relay's position.

## What happened

Two tasks of one project were started at once on a host. The second start's `setup` ended 75, "a poll of it was still
running after 45 s", and nothing was created.

- **Every `setup` on a central homeserver admits the project's relay**, which all the project's tasks share. Setup
  then forgets the relay's position if it was taken only in rooms the relay has left. To do that, it first takes the
  relay's poll lock, waiting at most 45 s, and only then asks whether there is anything to forget.
- **In an ordinary start there is nothing to forget**, since the relay is still in the room, yet setup still has to win
  the lock.
- **The lock is seldom free.** A `poll` holds it for its whole long sync, up to 30 s. Once the first task runs, Porelo
  polls the relay again with no pause after a wait that ended empty. So the lock is free only for milliseconds between
  two polls.

## Plan

- **`Lifecycle.forgetPosition` with a predicate first reads the position without the lock**; `poll` replaces that file
  in one atomic move, so the read is whole. Where the predicate says nothing goes, it returns at once and leaves the
  lock alone. Only a position that names its rooms can say so. A missing position, or one without rooms, still waits,
  since a poll still running may yet save one from rooms it read before the relay left them. A position that may go
  is asked again under the lock, as today. A read that fails without the lock falls back to the locked path, which
  reports the fault.
- **`clear`'s use, which forgets whatever there is, still waits for a running poll**, as it must.
- **The test that goes red first:** `ForgetPositionTest`. With a poll holding the relay's lock and the position taken
  in a room the relay is still in, setup's check ends at once and reports nothing kept; today it waits out its time
  and reports the position kept, which `setup` turns into 75. Beside it, a setup with no position yet still waits for
  the running poll, and an unreadable position is reported kept. The existing tests keep holding the rest: a position
  only in rooms left is still forgotten, a position saved during the wait is still judged as found under the lock,
  and `clear` still waits for a poll.

## Acceptance

- `ForgetPositionTest` passes, and its new case fails against the old code.
- The unit suite passes.
