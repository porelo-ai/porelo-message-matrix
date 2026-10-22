# MX28 — A Task Account Per Project

**Status:** Idea.

**What must be true.** Two tasks of two projects never share one Matrix account, even when their names run
together: a task's account is the project's and the task's, so retiring one never cuts off the other, and a
message from one is never taken for the other's.

## Why

`enroll --project P --task T` makes the account `@<T>`, and Porelo passes the container's name
`porelo-<project>-<task>` as `T`. The name does not separate the project from the task: project `a-b` with task
`c` and project `a` with task `b-c` are both `porelo-a-b-c`. On one homeserver the second `enroll` takes the
first task's account into its room, and `retire` of either deactivates both. Porelo passes `--project` with every
`enroll` and `retire`, so the transport can key the account by both.
