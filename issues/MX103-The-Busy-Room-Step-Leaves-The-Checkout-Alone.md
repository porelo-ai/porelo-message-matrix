# MX103 — The Busy Room Step Leaves The Checkout Alone

```json
{
  "$schema": "issue-1.json",
  "id": "MX103",
  "title": "The Busy Room Step Leaves The Checkout Alone",
  "state": "Review",
  "size": "XS",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "The rented-host run's busy-room step takes this repository's main from a fresh clone in its own scratch directory and never writes in a checkout of this repository"
}
```

**Why this size.** one shell step, `build/rehearse-steps/busy-room`, and its copy in porelo-host; a test script, proven
by running it.
**How it stands.** Review: built. The step was run on the development machine with a public clone and an unreachable
host: it cloned main, ran the rehearsal (75, the host unreachable) and left the checkout's worktrees, refs and objects
unchanged and no scratch directory. With an unresolvable source it ended 2 naming it. The run against a host follows in
the next rental, after porelo-host takes the file.

**What must be true.** The busy-room step of the rented-host run gets this repository's `main` without writing in any
existing checkout of it: no `git fetch`, no `git worktree add`, no ref or object written there.

## Plan

- **`build/rehearse-steps/busy-room`** clones `main` into its own `mktemp -d` directory instead of fetching in
  `REHEARSAL_MATRIX_CHECKOUT` and adding a worktree there: `git clone -q --depth 1 --branch main <source>` over https,
  from the public repository by default, or from `REHEARSAL_MATRIX_SOURCE` (a bundle or a path, read only) for a run
  without the network. A clone that fails ends the step 2, naming the source; there is no fallback to a checkout's
  last fetch.
- **The EXIT trap** removes only the scratch directory; `git worktree remove` in a checkout goes.
- **`REHEARSAL_MATRIX_CHECKOUT` goes**, and the step's header says where `main` comes from.
- **porelo-host's copy** is replaced by the new file as it is, by its agent.
- **Proof:** a test script, so no red-first test. The step runs once against a local VM's host with `git status` and
  `git worktree list` of every checkout of this repository unchanged before and after, then in the next rental.

## Acceptance

- After a run of the step, `git worktree list`, `git for-each-ref` and `git count-objects` of the developer's
  checkout read the same as before.
- With the network and no `REHEARSAL_MATRIX_SOURCE`, the step logs the clone's commit and runs the rehearsal from it.
- With an unreachable source, it ends 2 naming the source, and no scratch directory is left.
