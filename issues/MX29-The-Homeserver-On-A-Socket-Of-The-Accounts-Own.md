# MX29 — The Homeserver On A Socket Of The Account's Own

**Status:** Backlog.

**What must be true.** The account's own homeserver is reached through a unix socket only that account can
open, so no other account on the machine can stand in for it at any moment - not even for the milliseconds
the transport's check of the listener's owner leaves open while a homeserver dies.

## Why

The transport refuses a loopback listener that is not the account's own, and leaves a window of
milliseconds between that check and the request as an accepted risk. A socket under `$XDG_RUNTIME_DIR` has no
such window: the filesystem decides who may connect, at every connection.

## The parts

- **This repository:** the unit gives Tuwunel a unix socket instead of a loopback port, and since Java's
  `HttpClient` reaches no unix socket, the transport needs a relay of its own; `PORELO_MATRIX_HOMESERVER` and
  `setup`'s `reaches` name the socket.
- **porelo:** `HelperEnvironment`, which passes `PORELO_MATRIX_HOMESERVER` to the transport; `TaskConversations`
  in `app-messaging`, which reads `reaches` to keep an offline project on loopback; and the `MessagesJoined`
  answer, which names the homeserver's loopback port.
- **porelo-frontend:** its *Join* forwards that port over ssh to the person's computer, so a Matrix client there
  reaches `localhost:<port>`; it would forward the socket to a local port of its own choosing and show that port.

## To be checked

- How the socket relates to the listener published for another machine on the VPN, which stays TCP and which a
  socket under `$XDG_RUNTIME_DIR` does not replace.
