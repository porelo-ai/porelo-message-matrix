# MX59 — The VPN Path Goes

**Status:** Implementing; Estimated and Backlog (operator, 2026-10-30, sprint 6m);
blocked by [MX56](README.md#mx56), [MX57](README.md#mx57) and [MX58](README.md#mx58).
**Size:** M - deletions across `Publish`, `Certificate`, `MachineTokens`, `MachineTokenVerb`, `Lifecycle` (`admit`,
`dismiss`, the published name), `Keep`, `HomeserverToken`, the VPN units, `pom.xml` and the packages'
descriptions, with their tests and docs.

**What must be true.** A host admin and a project admin find no step, verb or setting that serves a homeserver to
another machine over a VPN, makes a CA or a certificate of the account's own, or hands out a registration token per
machine: the host's homeserver is reached over ssh through its gate, and nothing leads them to set up what nobody
reaches.

## The shape

- **Goes:** `publish --address`, `publish --name` and `publish --off`, with the VPN socket units, the drop-in
  `publish` writes, `tls.env`, `dual_protocol`, `PORELO_MATRIX_PUBLISH` and `PORELO_MATRIX_PUBLISH_NAME`, and the
  published name the account's own homeserver answers; the account's own CA and server
  certificate, placed certificates, and what `backup` and `restore` keep of them; `ca_certificate` in the settings;
  `machine-token issue|revoke|expel <machine>` with the token files and the known and expelled machines' records;
  `admit` and `dismiss`.
- **Stays:** `ca_file` for a homeserver elsewhere whose certificate an operator's CA signed, and
  `registration_token_file` and `PORELO_MATRIX_REGISTRATION_TOKEN` for such a homeserver.
- **The docs and packages:** "For another machine on the VPN" and "A person's machine on a host's homeserver" in
  `doc/homeserver.md`, every passage on what goes in `doc/transport.md` and `doc/decisions.md`, the packages'
  descriptions, and the decision "The homeserver on the
  VPN: TLS by Tuwunel, a token per machine" in `doc/decisions.md` go; what holds instead is the gate's decision.

## Plan

Read in `e8aadf8`.

- Delete `Publish`, `Certificate`, `MachineTokenVerb`, the rest of `MachineTokens`, the two VPN units and their
  packaging in `pom.xml`; `Lifecycle` loses `admit`, `dismiss` and the published name (`own`); `HomeserverToken`
  stops writing TLS settings; `Keep` stops keeping a CA; `Settings` and `SettingsCheck` refuse `ca_certificate`.
- Their tests go (`PublishTest`, `PublishTokenTest`, `CertificateTest`, `CertificatePlacedTest`, `MachineTokensTest`,
  the VPN cases of the integration tests); `MainTest` sees each verb refused as unknown.
- `doc/homeserver.md`, `doc/transport.md`, `doc/decisions.md`, the packages' descriptions and the change log.
- Red first: `MainTest` for `publish` and `machine-token` refused as unknown, and `SettingsCheckTest` for
  `ca_certificate`.

## Acceptance

- Each verb above is refused as unknown, and `settings` refuses `ca_certificate`, saying it went.
- `doc/` names none of them; the change log says they went.
- The integration tests that covered the VPN path are gone, and the whole workflow is green without them.
- Seen to fail: `publish` or `machine-token` still runs; a backup made before still restores a CA into the account.
