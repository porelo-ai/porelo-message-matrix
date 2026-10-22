# MX20 — The Local Homeserver Is Proven The Account's Own

**Status:** Review.
**Size:** M - a check before every request to a plain-`http` loopback homeserver, in the one place every verb
reads its URL, with a measurement of who owns the listener of a rootless container on the VM; nothing another
repository reads changes.

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

## Plan

- **Read in today's code:** every verb takes its URL through `Config.homeserver` (`Config.java:73`), which lets
  plain `http` through when `Lifecycle.loopback` holds (`Config.java:94`); `setup` builds its own
  `http://127.0.0.1:<port>` in `LocalHomeserver.ensure` (`LocalHomeserver.java:84`) and trusts the first 200 of
  `awaitUp`. Nothing asks whose the listener is.
- **The shape:** the listener proven the account's own. The other the issue named, a unix socket of the
  account's own across three repositories, is [MX29](README.md#mx29).
  Before a token goes to a plain-`http` loopback address, the transport reads the kernel's table of listening
  sockets (`/proc/net/tcp`, `/proc/net/tcp6`) and finds the one bound to that address and port. Its owner must
  be the transport's own uid, else 78 naming the port and the owner's uid, before any request. Another account
  cannot bind a port while this one holds it, so a listener that is the account's own at the check is the
  account's own at the request, but for a window of milliseconds while this account's homeserver dies between
  the two: the accepted risk. Nothing another repository reads changes.
- **Steps:**
  1. `Listener` reads the tables from paths it is given, so a test can hand it files, and refuses (78) a
     listener of the port, on its address or a wildcard, whose uid is not the process's own. Nothing listening
     is left to the connection, which fails as unreachable (75), as before.
  2. `MatrixClient`'s constructor calls it, the one place every verb's requests start from; `setup`'s client
     is made after the local homeserver answers. `https` keeps its certificate check as today.
  3. `doc/transport.md` names the 78, and `doc/decisions.md` says another account's listener is refused, with
     the window left as the accepted risk.
- **The test that goes red first:** `LoopbackOwnerIT`: a listener on a free loopback port in a container with
  a user namespace of its own (a sub-uid, as another account's would be), and `poll` with a token against it.
  Today the listener receives the request with the token; after, 78 and nothing received.

## Acceptance

- `LoopbackOwnerIT` red on today's code, green after; it runs where podman does, and is skipped, with its
  reason, where the homeserver runs as the test's own binary.
- `ListenerTest` on tables in the kernel's format: another uid on `127.0.0.1`, on `0.0.0.0`, on `::` or mapped
  into IPv6 is 78; the account's own listener, another port, and a socket only connected to the port pass.
- Every integration test against the native executable reaches its homeserver through the check.
- Both are seen to fail when the owner comparison is broken on purpose.

## Measured

- On the VM in rootless podman: the local homeserver's published loopback port is listened on by `pasta` under
  the account's own uid; a listener in a user namespace of its own, on the host's network, shows a sub-uid
  (166536 for uid 1009), so the check tells it from the account itself.
- The native executable reads the tables and its own uid as the JVM does: every integration test against it
  reaches its homeserver on plain-`http` loopback through the check.
