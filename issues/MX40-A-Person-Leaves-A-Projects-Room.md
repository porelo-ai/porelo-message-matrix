# MX40 — A Person Leaves A Project's Room

**Status:** Analyzed.
**Size:** S - one verb in `Lifecycle` and its tests, and one row of the contract `doc/transport.md`, agreed with `porelo`.

**What must be true.** When a person is removed from a project's conversation, that person can no longer read or
write in the project's room: `part --project P --person N` takes `N` out of `#porelo-P` and ends every session of
the account, without touching `N`'s place in any other project's room.

## Why

- `porelo talk remove` runs this verb, so a person the operator removes in the interface loses the room at once,
  not when the project is cleared.
- One account per person serves every project on the account's own homeserver; `clear` already keeps a person who
  is in another project's room, and `part` must not cut that person off either.
- A removed person may join again under the same name. A deactivated name can never be registered again on
  Tuwunel, so the account is locked, never deactivated.

## Plan

- **`part` in `Lifecycle`:** listed in `VERBS` and in the description; options `--project`, `--person`,
  `--loopback-only`; no `--machine`. On a central homeserver it is refused with 77, as `join` is.
- **On the account's own homeserver**, as the administrator `@porelo`:
  - `N` joined or invited in `#porelo-P`: kicked out of it (the membership reads `leave`). Not a member, or no
    account at all: nothing to do, success.
  - `N` in no other project's room (the same reading as `clear`'s `membersElsewhere`): every session of the
    account logged out and its password replaced by one nobody is shown, so a later `join` brings the person
    back. Where `N` is in another project's room too, the account stays as it is and is named in `kept`.
- **`join` takes a locked account back:** an existing account that is joined or invited in no project's room
  (`#porelo-*`) gets a new password, shown once, and is invited into P's room again, without `--reset`. An
  account in another project's room is still refused with 77 without `--reset`, as today.
- **The answer, exit 0 each, as `porelo` reads it:** `{"removed": "@N:server"}` when the account is locked and
  out of the room; `{"kept": "@N:server", "why": "a member of #porelo-Q too"}` when it is out of the room and
  stays for another; `{}` when there was nothing to do. Run again it finds less and succeeds.
- **`doc/transport.md`:** the row for `part`, and the lifecycle text naming it beside `join`.
- **The test that goes red first:** `LifecycleIT`: a person joined to two projects' rooms; `part` of the first
  leaves the person's membership `leave` there, `join` there in the second, and the person's old token still
  answering; `part` of a person in one room only makes the old token answer 401 and a `join` without `--reset` works again;
  `part` run twice exits 0.

## Acceptance

- Against the pinned Tuwunel: a removed person's old token can no longer read the room, and a person in another
  project's room keeps it there.
- The failure paths seen: a central homeserver refused with 77, a person's name that begins with `porelo` refused
  as `join` refuses it.

## To be checked

- Whether Tuwunel's admin room logs out every session of a user (`users logout`), and whether replacing a password
  does already.
