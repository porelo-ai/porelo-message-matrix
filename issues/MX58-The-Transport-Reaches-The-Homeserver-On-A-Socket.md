# MX58 — The Transport Reaches The Homeserver On A Socket

**Status:** Implementing; Estimated and Backlog (operator, 2026-10-30, sprint 6m).
**Size:** S - `MatrixClient`, `Config`, `Settings` and `Lifecycle.loopback`, with unit tests and the central integration
tests run once over a socket; no verb changes.

**What must be true.** The transport running in a person's account on a host reaches the host's homeserver through
its Unix socket, and a homeserver forwarded to a person's machine is reached under a `*.localhost` name as loopback,
so neither needs a VPN, TLS or a certificate of its own.

## The shape

The contract, in words:

- **`homeserver: unix:<absolute path>`** in the project's settings, and the same in `PORELO_MATRIX_HOMESERVER` for
  the verbs that read it, names the socket: a central homeserver like any other, never an offline project's. The server's name is what the homeserver answers for its accounts, as today. The transport speaks HTTP/1.1
  over the socket for every request it makes over TCP today; `setup` answers the socket's path where it answers the
  address it reached.
- **A `*.localhost` name** (`http://<host>.localhost:<port>`) is loopback: the transport connects to the loopback
  address the forward listens on without asking DNS, sends the name as the request's host, and checks the listener's
  owner as it does for `127.0.0.1` today.
- `settings` accepts both forms and refuses a relative socket path.

## Plan

Read in `e8aadf8`.

- `MatrixClient` sends through a small `Wire` of two kinds: today's `HttpClient` for `http` and `https`, and a new
  `SocketWire` that writes one HTTP/1.1 request (`Host`, `Authorization`, `Content-Type`, `Content-Length`,
  `Connection: close`) to a `SocketChannel` on a `UnixDomainSocketAddress` and reads status, headers and a body by
  `Content-Length` or chunked. Both give back status and bytes; `exchange`, `call` and `parse` stay as they are.
- `Config.homeserver` and `Settings` take `unix:<absolute path>`; `Listener.ensureOwn` is not asked for a socket,
  whose file modes guard it. `SettingsCheck --loopback-only` refuses `unix:`, an offline project's homeserver being
  this machine's own.
- `Lifecycle.loopback` takes a host ending in `.localhost`; `MatrixClient` sends a `*.localhost` request to
  `127.0.0.1` on the same port. `Lifecycle.reaches` answers the socket's path for `unix:`.
- Red first: `MatrixClientSocketTest` against a `FakeHomeserver` served on a Unix socket, and `ConfigTest` and
  `SettingsTest` cases for `unix:relative`, `foo.localhost.example.org` and `a.localhost`.

## Acceptance

- Every verb a central homeserver takes works the same over `unix:` as over `http://127.0.0.1`, shown by the
  central-mode integration tests run once each way.
- A `*.localhost` homeserver whose port another account listens on is refused (78), as for `127.0.0.1`. Seen to
  fail: a token is sent to it.
- Seen to fail: `foo.localhost.example.org` taken as loopback; a request over `unix:` going over TCP; `settings`
  taking `unix:relative/path`.
