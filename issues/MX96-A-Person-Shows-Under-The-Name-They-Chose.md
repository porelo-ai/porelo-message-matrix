# MX96 — A Person Shows Under The Name They Chose

**Status:** Backlog.
**Size:** M - the host commands' input and the gate's registration, with their contract in `doc/transport.md`, which
Porelo reads, and tests against the real homeserver.

**What must be true.** In a project's room a person shows under the name they chose once, such as `Ada`, never as their
whole Matrix id; the tasks' answers then name them by it. A name the person later sets in their own client stays theirs.

## Why

A person signed in on a host through the gate showed in the room as `@ada:matrix.<host>.porelo.internal`, and the tasks'
answers repeated that whole id. Nothing asked for a name: the organization keeps none for a person, the window does not
ask, and the gate registers the account with no display name. On the account's own homeserver `join --display` already
sets one; on a host nothing does.

## Plan

- **`keep-room` takes a name with each login**, `--persons ada=Ada,bob`, the name validated as `enroll`'s nickname
  (1 to 64 characters, no control characters, `@` or `:`); a login without one keeps today's behavior.
- **The name is set on the person's account when it has none yet**: when `keep-room` invites a login whose account
  exists with no display name, and when the gate registers the account of a login the host gave a name for. An account
  that already shows a name - the person's own choice in their client - is left as it is.
- **Where the name comes from is Porelo's**: the host's helper passes the name the organization keeps for the person;
  the transport only carries it into the room.
- **The test that goes red first:** `KeepRoomTest` against a scripted homeserver: a login named with a name gets it on an
  account without one, and an account with a name keeps its own; a name with `:` is refused (78). `KeepRoomIT` against
  the real homeserver behind its gate: a person signed in by SSO shows the given name in the room's member list.

## Acceptance

- On a host, a person whose name the host passes shows under it in the room and in the tasks' heads; one with a name of
  their own keeps it; a test fails if the id is shown where a name was given.

## To be checked

- Whether the administrator can set another account's display name on Tuwunel (an admin command or the client API as
  that account), or the gate must set it at registration while it holds the new account's token.
- Whether the gate learns the name from a file the host's helper writes, or only `keep-room` sets it.
