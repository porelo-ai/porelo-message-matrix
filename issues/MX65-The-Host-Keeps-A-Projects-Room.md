# MX65 — The Host Keeps A Project's Room

**Status:** Review.
**Size:** M - two new verbs beside `expel`, one rule less in the gate, a contract the host's helper and Porelo read, docs and an integration test against the real homeserver.

**What must be true.** On a host whose homeserver runs behind its gate, a project's room exists as soon as the host
carries the project, and holds exactly the persons the host names: a person named is invited whether or not they have
ever signed in, a person no longer named is out of that room with all their accounts, and no person's Porelo has to be
running for either. No person can make or take a project's room. When the host stops carrying the project, its room
is gone from that host, with its conversation; a homeserver elsewhere, named by the project, keeps its room as it does
today.

## Why

Today the project admin's Porelo makes the room (`setup --create-room`) and keeps it (`members`), as the room maker's
`@porelo.<login>`, from a list it reads itself. A project's members now live outside the host, the host alone follows
them, and the project admin need not be on the host at all: then nobody could make the room, every other member's
`setup` would stay "not admitted", and a removed member would stay in the room. The one actor always present is the
host's helper, which already runs `expel` as the homeserver's account with the administrator's token.

## The shape

- **`keep-room --project P --persons A,B,…`**, run by the host's helper as the homeserver's account, with the gate's
  administrator token among the secrets on stdin, reaching the homeserver through its private socket, as `expel` does.
  Only behind the gate; elsewhere refused (77).
- **It is declarative and idempotent.** It makes `#porelo-<P>:<server>` if it is missing, with the administrator as its
  maker, invite-only, closed to guests and unlisted; takes it only if the administrator made it, any other room under
  the alias being refused (78); then keeps it to the list by the rules `members` has today. Run again with the same
  list it changes nothing.
- **`drop-room --project P`**, run the same way when the host stops carrying the project: takes every member out,
  then deletes the room with its alias and its conversation. Nobody's account is deactivated, because a deactivated
  name stays taken and a person keeps their accounts for other projects and for a project put back, which starts with a
  new room. No such room: nothing to do, exit 0. A room the administrator did not make under the alias is refused (78).
  `--persons ""` on `keep-room` is a project with no members: its room stays.
- **It answers** `{"room":"#porelo-P:server","conversation":"!…","created_room":true|false,"invited":[…],"removed":[…]}`
  on stdout and exits 0; `drop-room` answers `{"room":"#porelo-P:server","deleted":true|false,"removed":[…]}`; each ends 75 when the homeserver is not reached or five rounds leave someone in, so the helper tries
  again at its next follow.
- **The gate refuses every client a reserved alias `porelo-<project>`.** The admins file
  (`/etc/porelo-host/matrix/<project>.admins`), `PORELO_MATRIX_GATE_ADMINS_DIR` and `GateRules.aliasAllowed`'s admin
  branch go.
- **A locked person is not named.** A member locked in a project goes off the list, so all their accounts are out of
  the room; a kick is no ban, so naming them again after an unlock invites them again.
- **A homeserver elsewhere has no administrator**, so a person there keeps its room by hand; `setup --create-room` and
  `members` go.
- **Nothing here assumes a homeserver on the host.** The transport's package needs none. Where no gate is prepared, both
  verbs are refused (77) and start nothing. A project that names a homeserver elsewhere may have an unused room here:
  the host cannot read that name, and Porelo goes by the project's file.
- **The project `default` has no room:** `keep-room` with `--project default` is refused (78), `drop-room` is not, so a leftover
  can go.
- **What stays as it is:** `setup` without `--create-room` joins once `@porelo.<login>` is invited, and says "not
  admitted" until then; a person's `@porelo.<login>` invites its own relay and tasks; `expel`; the accounts' names.
- **The administrator is a member of the room**, shown as "Porelo (administrator)", as in the account's own
  homeserver's room, because a kick needs its power. Removing it from view stays with the issue on helpers in the room.

## Plan

- `Main`: the verbs. `KeepRoom` (new), built like `Expel`: `gated` or refused, the token from stdin, `GateHomeserver`
  for the client.
- `CentralRoom`'s keeping moves into `KeepRoom`; `setup --create-room`, `members` and `CentralRoom` go. `logins` never
  reads `@porelo` as the bare account of a login `porelo`.
- `Lifecycle.room` and `Lifecycle.ours` are reused for making and taking the room, `AdminRoom.deleteRoom` for deleting it.
- `Gate.aliasRefusal`, `GateRules.aliasAllowed`, `GatePreparation`: the admins file goes.
- `Describe`, `doc/transport.md`, `doc/homeserver.md`, `doc/decisions.md`, the change log.
- Red first: `GateIT`: a project admin's `@porelo.<login>` creating `#porelo-<P>` through the gate is answered 403, today
  it passes. `GateIT` for the verb: a fresh homeserver, `keep-room` with two logins neither of which has signed in; the
  first signs in through the gate, its `setup` is admitted; the list drops the other; a task of a dropped login is out;
  `drop-room` leaves no room and no alias, and a later `keep-room` makes a new one the same person joins.

## Acceptance

- Measured against the real homeserver behind the gate: the room appears on the first run; a listed login that signs
  in afterwards joins with no further step; a dropped login's bare account, `@porelo.<login>`, relay and tasks are out of
  this room and still in another project's room.
- Measured the same way: after `drop-room` the conversation cannot be read by anybody, the persons' accounts still sign
  in, and another project's room is untouched.
- Seen to fail: a client of any login, a project admin's too, makes or takes the alias through the gate; `keep-room`
  without the administrator's token, without the gate, or on a room the administrator did not make changes anything or
  starts a homeserver;
  a run takes the administrator or a listed login's account out; a token appears in the answer, a log or an error.

## Measured

On the pinned Tuwunel behind the gate, on the host:

- `keep-room` with 500 logins, neither signed in: 9.8 s first, 0.14 s run again, 10.3 s taking them out, far inside the
  helper's five-minute follow.
- The administrator, as the room's creator in room version 12, kicks a joined member and an invited one through the
  private socket; a member's kick of it is answered 403.
- A host login `porelo` cannot arise here: `Login` and `GateRules.login` refuse it, and `Login.owner` reads no login out
  of `@porelo`.
- A member taken out reads nothing said after that, and still what was said up to it (`/messages`, not refused).
- A relay taken out, or whose room was deleted, answers 78 on `poll` and its tasks are out until Porelo runs `setup` and
  `enroll`, with `setup` forgetting a position from a room that went. Porelo running those two without a hand, after an
  unlock or on a new room, is Porelo's to hold.
