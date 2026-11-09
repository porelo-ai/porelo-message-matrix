# MX95 — The Transport Says Who Is In The Room

**Status:** Backlog.
**Size:** M - one new verb, its contract in `doc/transport.md`, which Porelo reads, and its tests on both homeserver
kinds.

**What must be true.** Porelo can ask the transport who is in a project's room - every joined and invited member, each
with the name the room shows for it and whether it is a task, a person or one of the room's helpers - so the window can
say who is in, and a person sees which name to write before a colon to reach a task.

## Why

A person found the window's room dialog saying "Nobody has joined yet" while two people and a task were in the room,
and the task shown under the agent's name (`Agent Claude`) where the person had to write `Agent Pi:` to reach it.
The transport shows each task under the name Porelo hands to `enroll --display`, or else the task's name; which name
that is stays Porelo's choice. No verb today tells Porelo who is in the room, so the window has no way to show it.

## Plan

- **A verb `members --project P`**, on the account's own homeserver as the administrator, and on a central homeserver
  with the login's token, the room resolved as `enroll` resolves it. It answers
  `{"room":"#porelo-P:server","members":[{"id":"@…","shown":"…","state":"join|invite","kind":"task|person|helper"}]}`,
  ordered by kind, then name. A task is an account `enroll` made (`@<task>` or `@<task>.<login>`), a helper the
  administrator, a relay or `@porelo.<login>`, a person any other account. No room: `{"members":[]}`, exit 0.
- **It reads, never changes**: no join, invite or profile write; on a host it answers the same through the gate.
- **The test that goes red first:** `LifecycleTest` against a scripted homeserver: a room with a task, a person, an
  invited person and the helpers answers each with its kind, state and shown name; a member with no display name is
  shown by its id; a missing room answers an empty list. `RoomIT` against the real homeserver: after `enroll` and
  `join`, `members` names both under the names they were given.

## Acceptance

- `members` lists exactly who the homeserver says is joined or invited, with the names a client shows, and a test fails
  when a member is left out, a helper is listed as a person, or a name differs from the room's.
- `doc/transport.md` describes the verb and its answer, and `describe` lists it.

## To be checked

- What the window reads today for "Already in the room", and whether Porelo asks the transport or keeps its own list.
