# MX109 — No Room Is Reached Through A Tunnel

```json
{
  "$schema": "issue-1.json",
  "id": "MX109",
  "title": "No Room Is Reached Through A Tunnel",
  "state": "Implementing",
  "size": "M",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "The path to a homeserver on another machine through a `unix:` socket goes: a socket is only a host's gate, which never receives a registration token, and the script laying agents' certificates on a host is gone"
}
```

**Why this size.** several classes and their tests, a script and its test, and the contract in `doc/transport.md`,
which `porelo` reads, change together; no new design.

**What must be true.** A `unix:` or `*.localhost` homeserver is only a host's homeserver behind its gate, reached on the
host itself. Nothing in this repository carries a task's room to a homeserver on another machine through a socket, and
no registration token ever goes over a socket.

## Why

Nothing runs between the development machine and the host's tasks: no tunnel, no bridge, no shared room. A moved agent
talks only in the room on the host's homeserver. The code that let a socket stand for a tunnel to a homeserver that
registers only with a token, and the script that lays local agents' certificates on a host, serve nothing any more, and
the token path over a socket is a way for a secret that no one needs.

## Plan

Read in today's code; what only the tunnel uses goes, what the host's gate or the rehearsal uses stays.

- **`Settings.tokenOptional()` and `Lifecycle.elsewhereToken`:** a `unix:` or `*.localhost` homeserver takes no token
  again. A token lent for one is dropped, never sent; the name goes back to saying a host's gate supplies registration.
- **`Accounts.register`:** the token stage is answered only off a socket. On a `unix:` homeserver whose challenge offers
  no dummy stage alone, it stops with 78 naming that the socket is no host's gate; the overload taking a
  `ListenerOwner` for the token goes.
- **`SocketWire.ForeignListener`** and its branch in `MatrixClient` stay only if the gate's or the rehearsal's own
  listener check still needs a 78 apart from 75 (`RehearsalClient` calls `listenedToBy`); otherwise they go with the
  token path. `MatrixClient.runningAccount()` stays: `GateHomeserver` and `RehearsalClient` use it.
- **Go entirely:** `build/lay-certificates.sh` and `LayCertificatesTest`; `RegisterOverASocketTest`;
  `CentralIT#setupAndEnrollWorkOverASocketTunnelWithALentToken` and its `tunnel` stand-in;
  `LifecycleTest#aTokenLentForASocketIsKept`; the `AccountsTest` cases of a token over a socket.
  `LifecycleTest#setupThroughASocketNotReachedIsTemporary` stays, reworded for the gate's socket: an unreachable socket
  is still 75.
- **Documents:** `doc/homeserver.md` loses "A host reaching the room on the development machine"; `doc/transport.md`
  says again that a `unix:` or `*.localhost` homeserver is a host's gate and receives no token; `doc/decisions.md`
  loses "A socket says nothing of what is behind it" and its index row; `AGENTS.md` no longer names
  `build/lay-certificates.sh` among the files that cannot be Java; a changelog line under *Removed*.
- **Red first:** `AccountsTest#aSocketWhoseChallengeAsksATokenGetsNone`: a stand-in homeserver on a `unix:` socket
  offering only the token stage, with a token lent. It must end 78 and the stand-in must have received no request
  carrying the token. Seen red on today's code, which sends it.

## Acceptance

- The red-first test above is green, and was seen red on today's code.
- `command grep` over the tracked files finds no `lay-certificates`, `ForeignListener` (unless kept for the gate, with
  the reason in this issue), `tokenOptional` or tunnel test left.
- The unit suite and `CentralIT` pass; the gate's and the rehearsal's tests pass unchanged.

## To be checked

- Whether `porelo` relies on a token reaching a `unix:` homeserver; the change of `doc/transport.md` is said to its
  agent before it is built.
- Whether `describe` keeps lending `PORELO_MATRIX_REGISTRATION_TOKEN` to `setup` and `enroll` for every homeserver
  elsewhere, unchanged: only a socket stops taking it.
