# MX42 — The Host's Tasks Reach The Room Over The VPN

**Status:** Implementing; its host side blocked by porelo [B304](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b304).
**Size:** L - crosses repositories (the unit and certificate here, the host's settings in porelo and porelo-host),
with a design to decide first.

**What must be true.** The account's own homeserver on this machine is also served on its VPN address over `https`,
with a certificate the rented host trusts, so the host's tasks talk in the same room as the local agents.

From [PJ59](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj59), before the first move.

## Why

- The unit publishes Tuwunel on `127.0.0.1` alone (`-p 127.0.0.1:${PORELO_MATRIX_PORT}:8008`), plain `http`.
- The transport takes a homeserver elsewhere only over `https`, with `ca_file`, and as a central homeserver: `room`
  set, `--machine` given and a registration token from `registration_token_file`
  ([MX12](README.md#mx12), [MX22](README.md#mx22)). Setting `homeserver` sends `setup` to its central path, which
  never starts the local homeserver.
- The account has one registration token (`HomeserverToken`, `TUWUNEL_REGISTRATION_TOKEN_FILE`), and no verb lets
  the local administrator invite another machine's `@porelo.<machine>` into a project's room.

## Plan

- **A second listener, on the VPN's address only.** `homeserver.conf` gains `PORELO_MATRIX_PUBLISH` (an address and
  port, e.g. `192.0.2.2:8448`). The unit publishes it only when set, never a wildcard address. The address exists
  only while a host is rented and its tunnel is up, so a VPN that is down must not keep the loopback listener down:
  how the unit waits for the address is decided in the build.
- **TLS on that listener.** The pinned Tuwunel serves TLS itself (`[global.tls]`, `certs` and `key`), but for every
  listener at once: without `dual_protocol` the loopback listener refuses plain `http` too, and with it every
  listener takes both `http` and `https`. So either Tuwunel serves TLS with `dual_protocol`, and the VPN listener
  also answers plain `http`, or a second pinned image in the pod terminates TLS for the VPN listener alone, which
  `PodmanUnit.image` and `ProcessUnit` then learn to take. Decided: the first, with the risk accepted in
  [the decisions](../doc/decisions.md#the-homeserver-on-the-vpn-tls-by-tuwunel-a-token-per-machine).
- **A registration token per machine.** `registration_token_file` takes several tokens, separated by whitespace; a
  token in it registers any number of accounts, and one removed from the file is refused after the homeserver
  re-reads it on `SIGHUP`. So `admit` adds a token of the machine's own to the file and `HomeserverToken` keeps the
  local one first; dropping a machine removes its token. Whoever holds a machine's token on the VPN can register
  accounts until it is removed: accepted in the same decision.
- **A certificate of the account's own** (a new `Certificate.java`): a CA made once, and a server certificate whose
  IP SAN is the published address. Both private keys are written `0600` before they are linked into place, as
  `HomeserverToken` writes the token. Only the server certificate and its key are mounted into the container,
  never the CA's key. The CA certificate is what the host names as `ca_file`. The server certificate holds for one
  year, and `setup` makes a new one within its last 30 days.
- **The host as one more machine:** its own settings name `homeserver`, `room` and `ca_file`, with a registration
  token, over the project file's, which keep this machine's homeserver its own; porelo's
  [B304](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b304) lets a machine's settings differ
  from the project file's. A new `admit --project P --machine M` lets the local administrator invite
  `@porelo.<M>` into the project's room, so no person has to.
- **`clear`, `backup` and `restore`:** `clear` removes the certificates and keys with the rest. `backup` keeps them;
  `restore` onto another machine drops `PORELO_MATRIX_PUBLISH` and the server certificate, which name this
  machine's address.
- **Red first:** a new `LocalHomeserverIT`: a client on the published address with the CA completes a TLS handshake
  and `/versions`, one without the CA is refused (78), and the loopback listener is unchanged; `admit` lets a
  second machine's `setup` answer `"admitted": true`.
- Files: the unit, `LocalHomeserver.java`, `HomeserverToken.java`, `Certificate.java`, `Lifecycle.java`,
  `PodmanUnit.java`, `ProcessUnit.java`, `Keep.java`, `LocalHomeserverIT`, `LifecycleIT`, `KeepIT`,
  `doc/homeserver.md`, `doc/transport.md`, `CHANGELOG.md`; porelo-host's VPN and the host's project settings.

## Acceptance

- A task on the host posts in the room, and a local agent's `room wait` ([MX41](README.md#mx41)) returns with it.
- Nothing listens on any address of this machine but loopback and the VPN's (`ss -ltn`).
- Seen to fail first: the handshake without the CA.

## To be checked

- Whether Tuwunel's admin command that issues a registration token can bound its uses, which would narrow the
  exposure of a machine's token.
- The shape of a machine's own settings with porelo's agent: which settings a machine may set for itself.
- How this relates to [MX29](README.md#mx29), the homeserver behind a unix socket, which a TCP listener on the VPN
  does not replace.
- That the host's Porelo reaches this machine's VPN address from the host itself, as read in porelo-host: the
  transport runs on the host, so no task needs a route to the homeserver. Measured once a host is rented.
