# MX28 — A Task Account Per Project

**Status:** Analyzed; porelo's [B246](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b246)
makes the names themselves unambiguous.
**Size:** S - one check in `enroll` and one in `retire`, the refusal in `doc/transport.md`, an integration test with
the real homeserver.

**What must be true.** Two tasks of two projects never share one Matrix account, even when their names run
together: a task's account is the project's and the task's, so retiring one never cuts off the other, and a
message from one is never taken for the other's.

## Why

`enroll --project P --task T` makes the account `@<T>`, and Porelo passes the container's name
`porelo-<project>-<task>` as `T`. The name does not separate the project from the task: project `a-b` with task
`c` and project `a` with task `b-c` are both `porelo-a-b-c`. On one homeserver the second `enroll` takes the
first task's account into its room, and `retire` of either deactivates both. Porelo passes `--project` with every
`enroll` and `retire`, so the transport can key the account by both.

## Plan

- **Read in today's code:** `enroll` makes `@<T>` through `Lifecycle.account` (`Lifecycle.java:637`). When `@<T>`
  exists and no token for it came with the call, it **sets a new password and logs every other session out**
  (`Accounts.alone`). So the second project's `enroll` does not only share the first task's account: it takes
  it over, and the first task's token stops working at once. `retire --project P --task T` deactivates `@<T>`
  whatever room it is in (`Lifecycle.java:241`).
- **[B246](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b246) is the cure for the names:** `porelo-<project>_<task>` can no longer be one name for two pairs. What
  stays for this transport is not to take over or deactivate a task account that is another project's, whoever
  passes such a name - a Porelo from before [B246](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b246), or a task named by hand.
- **The shape, decided:** refuse rather than rename. A task's account named by the pair would give every
  enrolled task a new address Porelo keeps, which is not worth it once [B246](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b246)'s names cannot meet:
  1. `enroll --project P --task T`: when `@<T>` exists and is joined or invited in another project's room
     (`membersElsewhere`, as `clear` asks since [MX19](README.md#mx19)), refuse with 78 before any password is set, naming the
     other room. `@<T>` in P's own room, or in none, is taken back as today.
  2. `retire --project P --task T`: the same check, so a retire through P never deactivates a task of another
     project.
  3. `doc/transport.md`: both refusals in the rows of `enroll` and `retire`.
- **The test that goes red first:** `LifecycleIT`: `setup` A and B, `enroll --project A --task porelo-a-b-c`,
  then `enroll --project B --task porelo-a-b-c`. Today the second succeeds and A's token answers 401. After:
  the second is 78 naming `#porelo-a:…`, and A's token still answers 200. The same for `retire --project B`.

## Acceptance

- `LifecycleIT` as above, red on today's code with A's token refused, green after.
- A task enrolled again in its own project still keeps its account and id (the existing `enrollAgain`).
- The check seen to fail when broken on purpose.
