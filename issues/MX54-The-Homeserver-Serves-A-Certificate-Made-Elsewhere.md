# MX54 — The Homeserver Serves A Certificate Made Elsewhere

**Status:** Implementing.
**Size:** M - one contract change another repository builds against, in `Certificate`, `Publish` and `Keep`, with
docs; no new verb.

**What must be true.** A published homeserver serves a server certificate and key that its host's administrator made
elsewhere, signed by a CA whose key never reaches the machine, and the transport never makes or replaces a CA or a
server certificate of its own beside them.

## The shape

The contract, in words:

- **What the host's tooling places**, owned by the homeserver's account, beside `homeserver.conf`: `ca.crt` (the
  CA's certificate, mode `0644`), `tls/server.crt` and `tls/server.key` (an EC key, `0600`, in `tls/` of mode `0700`). There is
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

## Plan

- `Certificate.placed(dir)`: `ca.crt` without `ca.key` beside `tls/server.crt`. `Certificate.ensure` then checks the
  placed certificate (signature, validity, the address's IP name, the name or a one-label wildcard, the key) and
  makes nothing; `removeServer` leaves it; `Certificate.notice` names its end in its last 30 days.
- `Publish.prepare` answers that notice; `homeserver-token` (every start) and `publish` print it on stderr.
- `Keep`: `backup` carries no placed CA, and `restore` takes a placed CA for no half CA.
- A CA of the account's own whose key is gone becomes a placed one: its server certificate is served until it
  ends, then refused, and the CA is never replaced.
- Tests, red first: `CertificatePlacedTest`, with a CA and certificates made by the JDK's `keytool`.

## Acceptance

- A placed certificate that covers the name by a wildcard is served unchanged through `publish` and a restart, and no
  `ca.key` appears.
- A placed certificate that has ended, names another host or is signed by another CA stops `publish` and the start
  with words, measured, and is never replaced by one of the transport's own.
