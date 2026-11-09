# MX96 — A Person Shows Under The Name They Chose

**Status:** Implementing.
**Size:** M - `keep-room`'s input and its naming of accounts, one fix in the gate's sign-in, their contract in
`doc/transport.md`, which Porelo reads, and tests against the real homeserver behind its gate.

**What must be true.** In a project's room a person shows under the name they chose once, such as `Ada`, never as their
whole Matrix id; the tasks' answers then name them by it. A name the person later sets in their own client stays theirs.

## Why

A person signed in on a host through the gate showed in the room as `@ada:matrix.<host>.porelo.internal`, and the tasks'
answers repeated that whole id. Nothing gave a name: the transport is handed none for a person, and the gate registers
the account with no display name. On the account's own homeserver `join --display` already sets one; on a host nothing
does.

## Plan

- **The names come on the input, never on the command line**: `keep-room --project P --persons ada,bob` stays as it
  is, and the input gains `{"settings":{"names":{"ada":"Ada"}},"secrets":{…}}`. A name may hold any character
  `enroll --display` allows, a comma or `=` among them, so no name can split into another login's entry. Each key must
  be a login of `--persons` (78 otherwise, naming the key); each name is held to the display rule, shared with
  `Lifecycle` (1 to 64 characters, no control or format characters, `@` or `:`), refused with 64 saying which login's
  name breaks which rule, never echoing the name. A login without a name keeps today's behavior.
- **The order of a run**: options, logins and names checked -> the gate and the administrator -> the room made or held
  to be the administrator's -> its members read and the ban check -> each named login's account made or named ->
  invitations -> those no longer listed taken out. Nothing is made or named on a run refused before.
- **A named account not made yet is made with its name**: under the gate's own `.users.lock`, as the gate makes an
  account (`username_available`, then `PUT /_synapse/admin/v2/users/{id}` with a secret nobody keeps and
  `"displayname"`), so it never exists unnamed and no sign-in races it. A login the gate expelled (`expelled-logins/`)
  is neither made nor named. A login given no name is not made, as today.
- **A named account that exists is named only when it shows none**: no `displayname`, or an empty one, so the Matrix id
  never shows. Any other name - the bare login too - is the person's own choice and stays, on every run. The name is set
  with `PUT /_synapse/admin/v2/users/{id}` and `{"displayname": …}` alone, if the pinned Tuwunel takes it there (see
  below); otherwise through a session the administrator opens as the account (`/_synapse/admin/v1/users/{id}/login`,
  `PUT /profile/{id}/displayname`, then that one session logged out - never the person's others; a failed logout is 75,
  and never hides an earlier failure). No token is printed or written.
- **The gate's sign-in takes an account made meanwhile**: `Gate.sso` treats `M_USER_IN_USE` from its own `create` as
  an account that exists and issues the login token, so a sign-in during `keep-room` is not refused.
- **The answer is unchanged**; whether a name was set is read from the homeserver, not from `keep-room`.
- **Where the name comes from is Porelo's**: the host's helper passes the name the organization keeps for the person.
- **The test that goes red first:** `KeepRoomTest` over `KeepRoom.keepTo` with the gate's directory handed in, against
  a scripted homeserver: an account not there is made with its name under the lock; one without a name is named, one
  with a name - its bare login too - keeps it; an expelled login is neither; a key that is no listed login is 78 and a
  name with `:` or a control character 64, each with no request made and the name not in the message. `GateTest`: a
  sign-in whose account is made between the gate's two asks gets its token. `KeepRoomIT`, folded into its existing
  run behind the real gate: the test's own login (the gate signs in the socket's user) named `Ada`, signed in by SSO
  and joined, shows `Ada` among the room's members; after the person sets `Ada L.`, a second run keeps it.
- **The contract**: the `keep-room` section of `doc/transport.md` and the gate's part of `doc/homeserver.md` say all
  of the above; Porelo's half is to pass the names.

## Acceptance

- On a host, a person whose name the host passes shows under it in the room from their first line on; one with a name
  of their own keeps it; a test fails if the id is shown where a name was given, or a name lands on another login.

## To be checked

- Whether the pinned Tuwunel takes `"displayname"` in `PUT /_synapse/admin/v2/users/{id}`, at creation and for an
  existing account, and leaves an account made there without one; measured against the real homeserver first.
