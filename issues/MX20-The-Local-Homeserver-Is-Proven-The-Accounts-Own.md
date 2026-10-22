# MX20 — The Local Homeserver Is Proven The Account's Own

**Status:** Analyzed.
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
  1. A small class `Listener` that reads the table from a path it is given, so a test can hand it a file, and
     answers the owner of a listening socket on an address and port, or none.
  2. `Config.homeserver` and `LocalHomeserver.ensure` (after `awaitUp`, before the registration token) call it
     for plain `http` on loopback; `https` keeps its certificate check as today.
  3. `doc/transport.md` names the 78, and `doc/decisions.md`'s row on plain `http` on loopback says another
     account's listener is refused, with the window left as the accepted risk.
- **The test that goes red first:** `ListenerTest`, on a table copied from a real one read on the VM, with the
  port's listener owned by another uid: today `Config.homeserver` lets the URL through; after, 78. Then on the VM,
  in the test account: a listener on the homeserver's port in a container with its own user namespace (owned by a
  sub-uid, as another account's would be), the unit stopped - `enroll` refuses before any request, seen by the
  listener receiving nothing.

## Acceptance

- `ListenerTest` red on today's code, green after; a table without the port, and one where the port is only
  connected, not listening, are each refused, so the check cannot pass on a missing row.
- On the VM, in the test account: with the homeserver up, `setup`, `enroll`, `poll` and `send` work as before; with another
  uid's listener on the port, each exits 78 and that listener logs no request.
- The guard is seen to fail when the owner comparison is broken on purpose.

## To be checked

- On the VM, whose uid the kernel shows for the listener of the homeserver's rootless container (pasta or
  rootlessport): it must be the account's own, or the check needs the container's process instead.
- Whether the native image reads `/proc/net/tcp` as the JVM does, measured with the built executable.
