# MX45 — A Project Admin Brings The Room Onto A Central Homeserver

**Status:** Implementing since 2026-10-29. Estimated by the operator on 2026-10-28; Accepted on 2026-10-28. It builds on Porelo naming a transport's machine `<host>-<account>`.
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

## To be checked

- Whether kicked accounts may rejoin by themselves: the room is invite-only, so not without a new invite; measured with
  a kicked `@task.<M>` trying to join.
- The power level the maker gives itself and the invite level of listed developers (today invite power is 0 in the
  room, so a developer's `@porelo.<M>` can invite its own tasks; a developer could also invite another, which the
  operator accepted under QH59).
