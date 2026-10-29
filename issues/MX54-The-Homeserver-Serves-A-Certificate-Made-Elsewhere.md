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

## Review

A read-only sub-agent (Antigravity CLI, gemini-4-argon-high) reviewed the change. What it found and was fixed, each
with a test seen red first: the placed certificate is checked as a client checks it (PKIX at the moment of the
check, the CA with `CA:TRUE`, `serverAuth` and `digitalSignature`), a wildcard covers a name only with at least two
labels under it and only in ASCII, a file holding a private key or more than one certificate is refused, a key the
transport cannot read is refused in words, `clear` and `restore` leave the placed files, and a refused `publish`
changes no setting.

Declined, with the reason:

- **A chain of intermediates:** one certificate per file. Tuwunel is handed `ca.crt` as the only anchor a client
  trusts, so an intermediate would need a bundle every client reads alike; a host's administrator signs the server
  certificate with the CA itself. The doc says so.
- **Races between the check and the start:** the administrator's tooling takes no lock of the transport's, so a
  lock here would order nothing. The files are placed while the homeserver is stopped, as the doc says; the next
  start checks them before it serves them.
- **The keys made in the tests:** they are made by `keytool` for one test each and never leave its directory.
- **Tests of the native image:** the unit tests run on the JVM; the VM acceptance runs the native binary with a
  placed certificate made by `openssl`, served through `publish`, a restart and `publish --off`.

## Acceptance

- A placed certificate that covers the name by a wildcard is served unchanged through `publish` and a restart, and no
  `ca.key` appears.
- A placed certificate that has ended, names another host or is signed by another CA stops `publish` and the start
  with words, measured, and is never replaced by one of the transport's own.
