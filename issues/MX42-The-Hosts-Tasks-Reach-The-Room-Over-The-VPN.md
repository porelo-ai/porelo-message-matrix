# MX42 — The Host's Tasks Reach The Room Over The VPN

**Status:** Review; this machine's side, published with `36adabe`. The host's side comes with porelo's
[B304](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b304), pushed in the same round.
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
  token in it registers any number of accounts, and the homeserver reads the file at every registration, so one
  removed from it is refused at once. So `admit` adds a token of the machine's own to the file (`MachineTokens`),
  which keeps the local one first; dropping a machine removes its token. Whoever holds a machine's token on the VPN can register
  accounts until it is removed: accepted in the same decision.
- **A certificate of the account's own** (a new `Certificate.java`): a CA made once, and a server certificate whose
  IP SAN is the published address. Both private keys are written `0600` before they are linked into place, as
  `HomeserverToken` writes the token. Only the server certificate and its key are mounted into the container,
  never the CA's key. The CA certificate is what the host names as `ca_file`. The server certificate holds for one
  year, and `setup` makes a new one within its last 30 days.
- **The host as one more machine:** the signed settings name the homeserver by the name it is published under, which
  this machine counts as its own and every other machine reaches as a central one. `admit --project P --machine M`
  gives the host a registration token of its own and invites `@porelo.<M>` into the project's room, so no person has
  to; `dismiss` bans its accounts and revokes the token; porelo's
  [B304](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b304) runs both. While a machine is
  admitted, its name ending `.<M>` is its own on this homeserver.
- **`clear`, `backup` and `restore`:** `clear` removes the certificates and keys with the rest. `backup` keeps them;
  `restore` onto another machine drops `PORELO_MATRIX_PUBLISH` and the server certificate, which name this
  machine's address.
- **Red first:** a new `LocalHomeserverIT`: a client on the published address with the CA completes a TLS handshake
  and `/versions`, one without the CA is refused (78), and the loopback listener is unchanged; `admit` lets a
  second machine's `setup` answer `"admitted": true`.
- Files: the unit, `LocalHomeserver.java`, `HomeserverToken.java`, `Certificate.java`, `Lifecycle.java`,
  `PodmanUnit.java`, `ProcessUnit.java`, `Keep.java`, `LocalHomeserverIT`, `LifecycleIT`, `KeepIT`,
  `doc/homeserver.md`, `doc/transport.md`, `CHANGELOG.md`; porelo-host's VPN and the host's project settings.

- **Declined after the reading:** two writers of `homeserver.conf` at once. `port()` writes the file only while it
  has no port, and `publish` writes the port itself first, so only a first `setup` racing a first `publish` could
  meet. They are run by different actors at different times, and a lost published address shows at once in
  `publish`'s answer. A file that already names its own `PORELO_MATRIX_PUBLISH` keeps it on `restore`: that is the
  restoring machine's own address, not the copy's. `settings` skipping its readability warning for a `~/` `ca_file` has no test: a
  wrong warning only reads wrong.

- **For the move (operator, the variant):** porelo-project's signed `project.yml` names
  `homeserver: https://matrix.porelo.internal:<port>` with the room, the CA as `ca_certificate` and
  `registration_token_file: ~/.config/porelo/matrix/host-registration-token`. The desktop resolves the name to loopback
  and keeps its own homeserver's path by the rule above, and the host resolves it to the desktop's VPN address. After
  the last move the project goes back to `{}`, with a new room on the host's own homeserver; the desktop's room and
  its homeserver data for the project are cleared then with `clear --project porelo` and `clear`, as the
  coordinator's move does.

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
