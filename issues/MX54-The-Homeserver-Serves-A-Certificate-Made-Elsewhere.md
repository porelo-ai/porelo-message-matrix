# MX54 — The Homeserver Serves A Certificate Made Elsewhere

**Status:** Idea.

**What must be true.** A published homeserver serves a server certificate and key that its host's administrator made
elsewhere, signed by a CA whose key never reaches the machine, and the transport never makes or replaces a CA or a
server certificate of its own beside them.

## The shape

The contract, in words:

- **What the host's tooling places**, owned by the homeserver's account, beside `homeserver.conf`: `ca.crt` (the
  CA's certificate, mode `0644`), `tls/server.crt` and `tls/server.key` (`0600`, in `tls/` of mode `0700`). There is
  no `ca.key`; its absence is what marks a certificate made elsewhere.
- **`publish --address <address>:<port> --name <name>` keeps them** while the server certificate verifies against
  `ca.crt`, fits its key, covers the published address and name (a wildcard such as `*.<host>.example` covers one
  label), and has not ended. It answers as today, with `ca_file` naming that `ca.crt`.
- **It never renews them.** Within the last 30 days every start says on stderr when the certificate ends. Once the
  certificate does not verify, does not cover the address or name, or has ended, `publish` and the unit's start
  refuse with `Exit.CONFIG` and say which, and nothing is written.
- **Without a placed server certificate nothing changes:** the transport makes the account's own CA and server
  certificate, as it does today.
- **A replaced certificate holds at the next start** (`systemctl --user restart porelo-matrix-homeserver`).

## Acceptance

- A placed certificate that covers the name by a wildcard is served unchanged through `publish` and a restart, and no
  `ca.key` appears.
- A placed certificate that has ended, names another host or is signed by another CA stops `publish` and the start
  with words, measured, and is never replaced by one of the transport's own.
