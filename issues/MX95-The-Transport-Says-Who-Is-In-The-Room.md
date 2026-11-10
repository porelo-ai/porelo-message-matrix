# MX95 — The Transport Says Who Is In The Room

```json
{
  "$schema": "issue-1.json",
  "id": "MX95",
  "title": "The Transport Says Who Is In The Room",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 6,
  "blockedBy": [],
  "covers": "`members --project P` tells Porelo who is joined or invited in a project's room, each with the name the room shows and whether a task, a person or a helper, so the window can say who is in"
}
```

**Why this size.** one new verb, its contract in `doc/transport.md`, which Porelo reads, and its tests on both homeserver
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

- **A verb `members --project P [--login L] [--loopback-only]`**, a lifecycle verb in `describe`, taking its settings and
  the account's secrets on stdin like `part` (in `Main.TAKES_INPUT`; no registration token lent, no machine secret
  needed). `P` is a name, `default` is refused (78) before anything is read, and `--loopback-only` is held as for the
  other verbs. It answers on stdout, exit 0:
  `{"room":"<alias>","members":[{"id":"@…","shown":"…","state":"join|invite","kind":"task|person|helper|other"}]}`;
  a `task` member also carries `"task":"<container>"`, and on a central homeserver `"login":"<login>"`, so Porelo maps
  it to its task by name, never by the shown name, which can change and repeat.
- **Which room**: on the account's own homeserver `#porelo-P:<server>`, held to the administrator's own room as `part`
  holds it (`ours`); on a central homeserver the configured room, as `enroll` resolves it, held to an invite-only room
  closed to guests (`checkCentralRoom`, never leaving it) with this project's relay `@porelo-relay-P.<login>` joined in
  it - otherwise 78, so another project's room is never answered under `P`. `room` in the answer is that alias.
- **Who counts**: members with `membership` `join` or `invite` only, asked as `/members?membership=join` and
  `?membership=invite`, each `id` once (`join` first). A room the caller's account is not joined to, a missing room, or
  no secrets from `setup` yet answer `{"room":…,"members":[]}`, exit 0 (before `setup` on the account's own homeserver
  `{"members":[]}`, since no room has a name yet; on a central homeserver after the login is checked) - never a
  homeserver started, a file written, an account registered or a session logged in or out.
- **Its kind, from the id alone**, helpers first: `@porelo`, `@porelo.<login>` and `@porelo-relay-<name>[.<login>]` on
  the room's server are helpers; `@porelo-P-<task>` (own homeserver) or `@porelo-P-<task>.<login>` (central) a task,
  `task` its localpart before `.<login>`; on a central homeserver a person only as `Login.person` reads one, on the
  account's own homeserver any other plain name not starting `porelo`; anything else `other`. A task's and a local
  person's name must follow the transport's name rule, and the server is what follows the id's first `:`; an id
  outside Matrix's syntax (printable ASCII, at most 255 bytes) is left out, so nothing unreadable reaches the window.
- **Its name**: the member event's `displayname`, with control and format characters removed and cut to 64 code
  points; the id when nothing is left. Ordered task, person, helper, other, then by `shown`, then by `id`.
- **It reads, never changes**: no join, leave, invite, kick or profile write.
- **The test that goes red first:** `LifecycleTest` against a scripted homeserver: a room with a task, a person, an
  invited person, a left and a banned account, the helpers and a stranger answers each with its kind, state and shown
  name, without the left and banned; a name with a control character is cleaned and an empty one shown by its id; a
  missing room and a room the caller left answer an empty list; a central room without the project's relay is 78;
  `default` is 78 with no request made. `RoomIT` against the real homeserver: after `enroll` and `join`, `members` names
  both under the names they were given.
- **Not in this issue, from the plan's reading**: a second `--project` silently replacing the first, and a homeserver's
  error text passed to stderr unfiltered, hold for every verb and go into an issue of their own.

## Acceptance

- `members` lists exactly who the homeserver says is joined or invited, with the names a client shows, and a test fails
  when a member is left out, a helper is listed as a person, or a name differs from the room's.
- `doc/transport.md` describes the verb and its answer, and `describe` lists it.

## To be checked

- What the window reads today for "Already in the room", and whether Porelo asks the transport or keeps its own list.
