# MX35 — A Person Knows The Room's Helpers

**Status:** Backlog.
**Size:** M - one contract field another repository reads, docs, two classes.

**What must be true.** A person in the project's room knows what the room's helper accounts are: each carries a
display name a person reads, "Porelo (administrator)" and "Porelo (delivery)", and a mention of only helpers wakes
no task and is answered with the standard line "This is Porelo's technical account; it passes your messages on to
the tasks and does not answer itself. Mention a task to reach it.", through porelo's half,
[B264](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b264).

## Why

- **What a client shows:** in Element, the room's members are the person, the task, `porelo` and
  `porelo-relay-<project>`. Tasks get a display name at `enroll` (`/displayname`), while the administrator and the
  relay get none, so a client shows their bare names and the operator cannot tell what the last two are.
- **What they are:** `@porelo` is the homeserver's administrator, which makes the room and registers and invites
  every account. `@porelo-relay-<project>` is the account Porelo's `poll` reads the room with. Neither writes in it.
- **Why a task wakes:** `Person.java` strikes the poller (the relay) from a person's `to`, but keeps `@porelo`.
  Porelo then drops it as neither a task nor a person, leaving `to` empty, and a line meant for nobody is meant for
  every agent, so a line mentioning only `porelo` wakes the task.

## Measured

On a test VM, against the pinned Tuwunel:

- **Administrator leaves:** once out of the room it cannot invite (`403 M_FORBIDDEN`). With invite power `50` given
  to the relay first, the relay invites a new account, it joins, and the relay still reads every new message with
  `/sync`.
- **Admin command without membership:** `!admin users force-join-room <user> <room>` is refused:
  "Auth check failed: cannot join a room that is not `public`". The admin API does not put an account into the
  invite-only room for an administrator who left it.
- **Application service:** `!admin appservices register` accepts a registration whose room namespace names the room;
  the homeserver pushes the room's events to the service's HTTP endpoint without the service being a member, and it
  is not shown among the members. But the service cannot read the room with `/messages` (`403 M_FORBIDDEN`): it gets
  the room only as pushes to a listening server.

## Plan

- **Read in today's code (`Lifecycle.java`, `Person.java`):** the administrator's membership carries every room
  operation: `room` and `ours` (finds the room by alias and proves Porelo made it by reading its first events and
  state), `admit` (invites tasks and people), `clear` and `membersElsewhere` (reads every project room it is in, so
  clearing one project never cuts a person off from another), and the central homeserver's verbs. `Person.java` strikes the poller from a person's `to` but keeps the administrator.
- **The helpers stay in the room, named in words:** `setup` sets the administrator's display name to "Porelo
  (administrator)" and the relay's to "Porelo (delivery)", also on an existing room, so a room made before shows them
  after the next `setup`.
- **Mentions of helpers marked for Porelo:** `Person.java` strikes both helpers from `to`, and a person's line whose
  `to` named only helpers is handed over marked with `"helpers": true` in the person file, absent otherwise and never
  `false`, with `to` empty then (`doc/transport.md`, the contract with `porelo`), so porelo's half
  [B264](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b264) wakes no task and answers with the
  standard line through `send`, run with the relay's token, so the relay's account answers and no task speaks. The
  transport itself never answers: it decides nothing. Red first, besides: `send` run with the relay's token, which no
  test runs today.
- **This is the interim:** a room that shows no helper at all is [MX38](README.md#mx38)'s, for a later round.
- **Not chosen here:** the administrator leaving the room moves every room operation and the cross-project checks to the
  relay and back (size L, the central homeserver too); the relay as an application service needs a listening service
  in place of `poll` and a new contract with `porelo` (size L, and a service where today a program runs).
- **Files and tests:** `Lifecycle.java` (`setup`, the display names), `Person.java` (the strike and the mark),
  `doc/transport.md` (the field), and `CHANGELOG.md`; tests in `PersonTest` (a line naming only the administrator:
  red today, `to` empty and no mark) and a `Lifecycle` integration test reading both display names after `setup`.

## Acceptance

- After `setup`, a client reads both helpers by the words chosen, on a new and an existing room.
- A person's line mentioning only a helper reaches Porelo with `to` empty and the mark, and is seen to fail without
  the fix.
- A line naming a task and a helper names only the task, unmarked.

## To be checked

- On a test VM, against the pinned Tuwunel with a client: whether Element shows the display name in the member list
  and in mention completion.
