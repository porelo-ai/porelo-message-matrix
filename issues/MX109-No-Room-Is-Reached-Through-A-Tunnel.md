# MX109 — No Room Is Reached Through A Tunnel

```json
{
  "$schema": "issue-1.json",
  "id": "MX109",
  "title": "No Room Is Reached Through A Tunnel",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "The path to a homeserver on another machine through a `unix:` socket goes: a `unix:` or `*.localhost` homeserver is only a host's gate and is never handed a lent registration token, and the script laying agents' certificates on a host is gone"
}
```

**Why this size.** several classes and their tests, a script and its test, and the contract in `doc/transport.md`,
which `porelo` reads, change together; no new design.

**What must be true.** A `unix:` or `*.localhost` homeserver is only a host's homeserver behind its gate, reached on the
host itself. Nothing in this repository carries a task's room to a homeserver on another machine through a socket, and
no registration token lent from a person's vault ever goes to a host's gate; only the gate's own homeserver takes its
token, over a socket whose listener is checked as the gate's account.

## Why

Nothing runs between the development machine and the host's tasks: no tunnel, no bridge, no shared room. A moved agent
talks only in the room on the host's homeserver. The code that let a socket stand for a tunnel to a homeserver that
registers only with a token, and the script that lays local agents' certificates on a host, serve nothing any more, and
the token path over a socket is a way for a secret that no one needs.

## Plan

Read in today's code; what only the tunnel uses goes, what the host's gate or the rehearsal uses stays.

- **`Settings.hosted()` and `Lifecycle.elsewhereToken`:** a `unix:` or `*.localhost` homeserver takes no lent token
  again; one lent for it is dropped, never sent.
- **`Accounts.register`:** the overload taking a `ListenerOwner` for the token goes. On a host's gate whose challenge
  offers no dummy stage alone it stops with 78, naming that the homeserver is no host's gate, unless the client checks
  its socket's listener (`MatrixClient.checksListener()`): that is `gate --prepare` registering `@porelo` on the gate's
  own homeserver with that homeserver's token, as before the tunnel.
- **Kept:** `SocketWire.ForeignListener`, its 78 in `MatrixClient`, `listenedToBy` and `ListenerOwner`, since
  `RehearsalClient` checks the gate's socket listener with them; `MatrixClient.runningAccount()`, which `GateHomeserver`
  and `RehearsalClient` use.
- **Gone:** `build/lay-certificates.sh` and `LayCertificatesTest`; `RegisterOverASocketTest`'s token cases;
  `CentralIT#setupAndEnrollWorkOverASocketTunnelWithALentToken` and its `tunnel` stand-in;
  `LifecycleTest#aTokenLentForASocketIsKept`. `LifecycleTest#setupThroughASocketNotReachedIsTemporary` stays, reworded
  for the gate's socket: an unreachable socket is still 75.
- **Documents:** `doc/homeserver.md` loses "A host reaching the room on the development machine"; `doc/transport.md`
  says again that a `unix:` or `*.localhost` homeserver is a host's gate and is handed no token; `doc/decisions.md`
  loses "A socket says nothing of what is behind it" and its index row; `AGENTS.md` no longer names
  `build/lay-certificates.sh`; the unreleased changelog entry of the tunnel's token path is taken out.
- **Red first:** `RegisterOverASocketTest#aSocketWhoseChallengeAsksATokenGetsNone` (a `unix:` stand-in offering only the
  token stage, a token lent: 78 and no request carries it) and `LifecycleTest#aTokenLentForAHostsGateIsDropped`, both
  seen red on the code before; `RegisterOverASocketTest#theGatesOwnHomeserverTakesItsTokenFromAClientThatChecksItsListener`,
  seen red on the first form of the change, which broke `gate --prepare`; `AccountsTest#aLocalhostNameAskingATokenGetsNone`,
  seen red with the check narrowed to sockets.

## Acceptance

- The red-first tests above are green, and were each seen red.
- `command grep` over the tracked files finds no `lay-certificates`, `tokenOptional` or tunnel test left.
- The unit suite and the integration tests pass, the gate's (`GateIT`, `KeepRoomIT`) and the rehearsal's among them.
