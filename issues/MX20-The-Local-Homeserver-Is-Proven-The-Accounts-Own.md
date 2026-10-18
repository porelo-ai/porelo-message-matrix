# MX20 — The Local Homeserver Is Proven The Account's Own

**Status:** Accepted; found when the code was read against its skills.

**What must be true.** A token for the account's own local homeserver reaches that homeserver and nothing else,
even on a machine with several accounts: another account that listens on the same loopback port gets none of
them.

## Why

The local homeserver listens on `127.0.0.1:<port>`, and the transport talks to it in plain `http`.
`LocalHomeserver` picks a port that is free when it looks and then trusts whatever answers 200 there. While this
account's homeserver is down - stopped, or after a reboot before its unit starts - another account on the
machine can listen on that port: `setup` then sends it the registration token, and every later `enroll`, `poll`
and `send` the admin, relay or task token. [`doc/decisions.md`](../doc/decisions.md) accepts plain `http` on
loopback against anyone on the way; another account on the same machine is not on the way, it is the other end.

## The shape, to be analysed

- **A socket of the account's own**, for example a unix socket under `$XDG_RUNTIME_DIR` that only the account can
  open, in place of the loopback port. That changes `PORELO_MATRIX_HOMESERVER` and what `setup` answers as
  `reaches`.
- **The porelo side that follows it:** `HelperEnvironment`, which passes `PORELO_MATRIX_HOMESERVER` to the
  transport; `TaskConversations` in `app-messaging`, which reads `reaches` to keep an offline project on
  loopback; and porelo's `MessagesJoined` answer, which names the homeserver's loopback port.
- **The porelo-frontend side:** its *Join* forwards that same port over ssh to the person's computer, so that a
  Matrix client there reaches `localhost:<port>`. With a socket it would forward the socket to a local port of
  its own choosing and show that port, and `MessagesJoined` would name the socket.

## To be checked

- Whether Tuwunel listens on a unix socket, and whether Java's `HttpClient` can reach one, or a small relay in
  the transport is needed.
- Whether checking that the port belongs to the unit's own container, through podman, is enough without a
  socket, and changes nothing another repository reads.
