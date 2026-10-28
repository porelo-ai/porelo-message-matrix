# MX46 — A Machine's Token Holds At Once

**Status:** Estimated by the operator on 2026-10-28.
**Size:** S - the unit's mount and `HomeserverToken`/`MachineTokens` in this repository, an integration test; no
contract another repository reads.

**What must be true.** A machine admitted with `admit` registers with its token at once, and a machine let go with
`dismiss` is refused at once, without restarting the account's homeserver.

## Plan

Seen in sprint 6i on the laptop (28 Oct): the host's `setup` got `401 M_FORBIDDEN: Invalid registration token` with
the right token. `MachineTokens.write` replaces `registration-token` by an atomic rename. The unit bind-mounts that
single file (`homeserver/porelo-matrix-homeserver.service`,
`-v %E/porelo/matrix/registration-token:/run/secrets/registration-token:ro`), and a file bind mount keeps the inode
it had when the container started. In the running container the file still held only the local token: 43 bytes and
the old inode, against 88 bytes on the host. A restart of the unit fixed it. `dismiss` has the same flaw the other
way round: a revoked token stays valid until the next restart.

- Mount the directory, not the file: the token file moves to `$XDG_CONFIG_HOME/porelo/matrix/tokens/registration-token`
  (`HomeserverToken.file`), the unit mounts `%E/porelo/matrix/tokens:/run/secrets/tokens:ro,Z`, and
  `TUWUNEL_REGISTRATION_TOKEN_FILE` names `/run/secrets/tokens/registration-token`. A rename inside a mounted
  directory is seen at once.
- `homeserver-token` (the unit's `ExecStartPre`) moves an existing `registration-token` into `tokens/` once, so an
  account set up before keeps its tokens.
- Red first: `LocalHomeserverIT` admits a machine **while the homeserver runs** and registers with its token, then
  dismisses it and registers again. Today the first fails with 401 and the second succeeds.

## Acceptance

- In the integration test, a machine admitted after the homeserver started registers at once, and after `dismiss` its
  token is refused at once, both without a restart.
- An account whose token file lies at the old place still starts, and its local token is the same afterwards.

## To be checked

- Whether anything outside this repository names `~/.config/porelo/matrix/registration-token`. porelo-host's stop
  writes `host-registration-token`, a different file for central mode. Read porelo and porelo-frontend before
  building.
