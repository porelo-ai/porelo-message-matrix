# MX35 — A Person Knows The Room's Helpers

**Status:** Accepted.

**What must be true.** A person in the project's room sees no helper account, or needs none at all: the room shows
the person and the tasks. Only if a helper must stay visible, it carries a name a person reads, and it answers a
mention with a standard line, such as "This is a technical account for delivering the room's messages; it does not
read along." porelo's half, [B264](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b264), a word to a
helper waking no task, is built only in that case.

## To be checked

- **Seen on 2026-10-23 by the operator, in Element:** the room's members were the person, the task, `porelo` and
  `porelo-relay-<project>`, shown by those bare names. The operator could not tell what the last two were. A line
  mentioning only `porelo` woke the task, which answered.
- **What they are:** `@porelo` is the homeserver's administrator, which makes the room and registers and invites
  every account. `@porelo-relay-<project>` is the account Porelo's `poll` reads the room with. Neither writes in it.
- **Why the task woke:** the transport strikes the poller, the relay, from a person's `to` (`Person.java`), but
  keeps `@porelo`. porelo then drops it as neither a task nor a person, so `to` was empty, and a line meant for nobody
  is meant for every agent.
- **On the VM, against the pinned Tuwunel:**
  - whether the administrator can leave the room after handing the right to invite on, and the transport still
    enrolls tasks and joins people;
  - whether it can invite through the homeserver's admin API without being a member;
  - whether the relay can be an application service that receives the room's events without being a visible member,
    if Tuwunel supports it.
- Only if a helper must stay visible: its display name, and how it answers a mention when today neither account
  reads the room for that.
