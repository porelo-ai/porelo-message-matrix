# MX105 — A Stopped Task Leaves Its Own Homeserver's Room

```json
{
  "$schema": "issue-1.json",
  "id": "MX105",
  "title": "A Stopped Task Leaves Its Own Homeserver's Room",
  "state": "Idea",
  "size": "S",
  "priority": "Medium",
  "rank": 6,
  "blockedBy": [],
  "covers": "On the account's own homeserver, a verb takes a stopped task's account out of the project's room without deactivating it, so the next `enroll` brings the same account back"
}
```

**Why this size.** One verb, or a mode of `retire`, on the account's own homeserver only: the administrator kicks
`@<T>` out of the room and logs out its sessions, and `enroll` invites a kept account back. Plus its tests against
the real homeserver and a row of the contract. A central homeserver needs nothing, since `retire` already does this
there.
**How it stands.** Idea (2026-11-11): filed on the coordinator's word after the rental run's busy-room proof. The
operator decides when.

**What must be true.** On the account's own homeserver, Porelo can take a stopped task out of the project's room
without deactivating its account. `members` no longer lists it, the room's "@room" does not count it, and a later
`enroll` of the same task brings the same account back into the room under its name.

## Why

A task stopped with `porelo task stop` keeps its account in the room. A person then speaks to a member that never
answers. The rehearsal's busy-room step found this on a central homeserver (Agent Coordinator, 08:15Z). There,
Porelo's B615 makes `task stop` run `retire`, which leaves the room and keeps the name free. On the account's own
homeserver, `retire` deactivates `@<T>` for good, so it fits removing a task but not stopping one. A stop there has no
verb yet.
