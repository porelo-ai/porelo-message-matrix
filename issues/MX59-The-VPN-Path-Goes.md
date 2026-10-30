# MX59 — The VPN Path Goes

**Status:** Accepted; blocked by [MX56](README.md#mx56), [MX57](README.md#mx57) and [MX58](README.md#mx58).

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

## Acceptance

- Each verb above is refused as unknown, and `settings` refuses `ca_certificate`, saying it went.
- `doc/` names none of them; the change log says they went.
- The integration tests that covered the VPN path are gone, and the whole workflow is green without them.
- Seen to fail: `publish` or `machine-token` still runs; a backup made before still restores a CA into the account.
