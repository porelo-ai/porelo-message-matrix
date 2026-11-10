# MX29 — The Homeserver On A Socket Of The Account's Own

```json
{
  "$schema": "issue-1.json",
  "id": "MX29",
  "title": "The Homeserver On A Socket Of The Account's Own",
  "state": "Backlog",
  "size": "-",
  "priority": "Medium",
  "rank": 5,
  "blockedBy": [],
  "covers": "The account's own homeserver behind a unix socket only it can open, across this transport, porelo and porelo-frontend, closing the window the check of the listener's owner leaves"
}
```

**What must be true.** The account's own homeserver is reached through a unix socket only that account can
open, so no other account on the machine can stand in for it at any moment - not even for the milliseconds
the transport's check of the listener's owner leaves open while a homeserver dies.

## Why

The transport refuses a loopback listener that is not the account's own, and leaves a window of
milliseconds between that check and the request as an accepted risk. A socket under `$XDG_RUNTIME_DIR` has no
such window: the filesystem decides who may connect, at every connection.

## The parts

- **This repository:** the account's own homeserver takes the way a host's already has behind its gate. There the unit
  gives Tuwunel a unix socket under `$XDG_RUNTIME_DIR`, the transport reaches it with `SocketWire`, and
  `PORELO_MATRIX_HOMESERVER` (`unix:…`) and `setup`'s `reaches` name it. On the account's own homeserver
  `LocalHomeserver` still starts a loopback port and `Listener` still checks its owner.
- **porelo:** `TaskConversations` in `app-messaging` reads `reaches` to keep an offline project on loopback, and its
  `loopback` counts only `127.0.0.1`, `::1` and `localhost`, so a socket's path must count as this machine. Porelo
  passes on unchanged the login `join` answers, with `loopback` and `port`.
- **porelo-frontend:** its *Join* reads that login as `MessagesJoined` and forwards the port over ssh to the person's
  computer, so a Matrix client there reaches `localhost:<port>`; it would forward the socket to a local port of its own
  choosing and show that port.
