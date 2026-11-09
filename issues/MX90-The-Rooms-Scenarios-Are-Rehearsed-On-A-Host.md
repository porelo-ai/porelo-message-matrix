# MX90 — The Room's Scenarios Are Rehearsed On A Host

**Status:** Backlog; blocked by [MX89](README.md#mx89).
**Size:** S - one step list for the rehearsal and one try on a local VM host; no code that ships.

**What must be true.** A rehearsal on a host proves, unattended, that what a person writes in the project's room
reaches exactly the tasks it is meant for, and that a task's answer comes back to the person.

## Plan

- **One step list, run by `RehearsalClient` as the test person**, each step judged on the host by `porelo talk log
  <task>` (what reached each task and whether it woke it) and in the room by what the client reads back. Tasks run
  on an API-key provider, so no agent's browser sign-in is needed.
- **The steps:**
  - `Agent <A>: …` typed with no pill, and once with a pill: A has it meant for it and is woken; every other task has
    it not meant for it and is not woken.
  - A head naming a longer or hyphenated display name beside a task with a shorter one: only the whole name is named.
  - `All agents: …`, and a line mentioning `@room`: every task has it marked for all and is woken.
  - A line naming nobody: no task is woken.
  - After a line meant for A: A's answer appears in the room from A's account, after the line.
  - Reading the room: every member's lines in order; no helper account shows.
  - An unencrypted direct chat with A: only A gets it, and A answers there.
- **Output:** one line per step, `OK` or `FAIL`, with what was asked and what `talk log` and the room answered,
  into the rehearsal's run directory; fail fast on a repeated failure. About 10 minutes, mostly waiting for answers.
- **The test that goes red first:** the step list run against a host on a local VM whose tasks are left unenrolled
  shows every step `FAIL`; enrolled, every step `OK`.

## Acceptance

- On a local VM host with at least three tasks of one project, every step is `OK`, and a task taken out of the room
  turns its steps `FAIL`, naming the task.
- One run on a rented host, on the operator's word.
