# MX45 — A Project Admin Brings The Room Onto A Central Homeserver

**Status:** Review since 2026-10-29. Estimated by the operator on 2026-10-28; Accepted on 2026-10-28. It builds on Porelo naming a transport's machine `<host>-<account>`.
**Size:** M - two lifecycle verbs in central mode, read in today's local `room()`/`ours()` and central `setup`, a
contract change porelo reads (`describe`, the answers), docs and integration tests.

**What must be true.** On a central homeserver, the project admin's Porelo makes the project's room and keeps its
members to the project's signed developer list: every listed developer's Porelo is invited and can join, every one the
list drops is out of that room, and nothing else in the room or on the server is touched.

## Plan

Read in `36adabe`. Central mode today takes an existing `room` and waits until a person invites `@porelo.<M>`
(`setupCentral`, `joinCentralRoom`, `checkCentralRoom`). The local mode already makes a room the way Porelo needs
(`room()`: `private_chat`, unlisted, guests forbidden; `ours()`: taken only if Porelo's own administrator made it).

- **`setup --project P --machine M --create-room`** (central mode): after `@porelo.<M>` is registered, it makes
  `#porelo-<P>:<server>` with local mode's `room()`, `@porelo.<M>` as its maker, or takes the room under that alias
  only if `ours()` says `@porelo.<M>` made it; any other room under the alias is refused (78). The answer says
  `"created_room": true`, so porelo knows which account holds the room. Without `--create-room`, central `setup` is
  as today.
- **`members --project P --machines M1,M2,…`**, run by the account that made the room, declarative and idempotent:
  - invites each listed `@porelo.<M>` not yet invited or joined; a machine not yet registered can be invited (measured
    in [MX42](README.md#mx42) on Tuwunel 1.9.4);
  - for each machine with members in the room that the list no longer names, kicks every `@….<M>` from that one room,
    `@porelo.<M>` first, and reads the room again until none is left;
  - never touches a person's account, `@porelo.<M>` of the maker itself, a machine still listed, or another room; an
    empty list takes out every machine but the maker;
  - answers `{"invited":[…],"removed":[…]}`.
- **`describe`** offers both (`lifecycle` gains `members`; `takes` gains `create-room`).
- The listed machines' names are checked as `--machine` is, and the list is bounded (the room's own limit).

Tests, red first: `LifecycleTest` for the arguments and refusals; `CentralIT` for a project admin who makes the room,
lists two developers whose Porelo join and enroll tasks, then drops one: that one's tasks and `@porelo` are out of
this room and still in another project's, a person's account and the remaining developer stay.

## Acceptance

- Measured against Tuwunel: the room is invite-only with guests forbidden; a listed developer not yet registered joins
  once they register; a dropped developer's accounts are out of this room only.
- `members` twice with one list changes nothing the second time.
- A room under the alias that the project admin's `@porelo.<M>` did not make is refused, and nothing is invited into
  it.

## Review

A read-only review by another model found the following, and each is fixed with a test that failed first:

- **A dropped machine's tasks stayed** once its `@porelo` and relay had left, by themselves or in a `members` cut
  off. A machine now counts as in the room by its `@porelo` of any membership, one that left too.
- **Another project's relay read as a machine:** a project's name may hold a dot (`demo.v2`), so
  `@porelo-relay-demo.v2.<M>` looked like the machine `v2.<M>`. A relay's name is no longer read.
- **A task named `porelo.worker` read as the machine `worker.<M>`**, whose accounts were then taken out. Central
  `enroll` now refuses a task named `porelo.…` (78).
- **Nested machines** (`x.hosta` beside `hosta`): an account `@job.x.hosta` is either's, so dropping one kept or
  kicked the other's task. A listed machine's account is never touched now: an account counts as a dropped
  machine's only if no listed machine's name could claim it, while a dropped machine's `@porelo` and its relay in
  this project are its own by name. Refusing nested names, tried first, let any member lock `members` by one
  invitation and refused renaming a machine; the readings found both. Left: a dropped machine's task whose name
  also ends as a listed machine's stays in the room, which needs two nested machine names in one project.
- **One dropped machine could invite another back** between their kicks. All dropped machines' accounts are kicked
  in the same rounds now, as `dismiss` does, and each round reads anew which machines are in, so one invited
  meanwhile is taken out too. A kick refused because the account left by itself is no failure.
- **A banned listed machine** was refused only after earlier ones were invited. The ban is checked first.
- **Settings naming another room** registered `@porelo.<M>` before `--create-room` refused. Now `setup` refuses
  a room of another name before any account is made. The alias on another server is told only once the account
  shows the server's name, so that refusal still comes on the next setup.
- **Tests that missed it:** nothing read `"created_room"`; the empty list was checked by the answer, not the room;
  the room made by another was refused before its maker was compared. Each is checked now.

Decided under the plan's "every `@….<M>`": on a room the project admin keeps, every `@<name>.<M>` counts as the
machine's, so a person takes a name ending as no machine's (doc/homeserver.md says so). A person's account cannot be
told from a task's by its name, and Porelo names the tasks.

## Measured on Tuwunel 1.9.4

- **A kicked account cannot come back by itself:** a dropped developer's `@t1.<M>` that tries to join the invite-only
  room is answered 403.
- **The room is of version 12, so its maker holds it by the create event**, above every level, with no entry under
  `users`. Invite is 0 and kick 50, so a developer's `@porelo.<M>` invites its own tasks, and could invite another
  as the operator accepted under QH59, but takes nobody out.
- **A room under the alias that another account made** is refused by `setup --create-room` with nothing invited:
  a first setup keeps the account and answers `"admitted": false`.
