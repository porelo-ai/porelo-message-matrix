# MX44 — The Host's Homeserver Gives Each Person A Token

**Status:** Estimated by the operator on 2026-10-28; Accepted on 2026-10-28, for
[PJ78](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj78).
**Size:** M - three verbs on an account's own homeserver with no project, read in today's `MachineTokens` and
`AdminRoom`, the homeserver's administrator made without a project's `setup`, docs and an integration test; no
contract another repository reads beyond the verbs' answers.

**What must be true.** The host admin's helper gives every person on a prepared host a registration token of their own
for the host's shared homeserver, and takes it away again, without a project and without any room: a person taken off
the host has no account left that can log in or stay in a room, and no other person's token or account is touched.

## Plan

Read in `36adabe`. The host's homeserver is an account's own homeserver (`porelo-matrix`,
[HO11](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho11)'s unit, published by
[MX42](README.md#mx42)'s `publish --name matrix.<host>.porelo.internal`), and every person on the host reaches it in
central mode as `<host>-<user>` (porelo's machine name). What is missing is the administrator's side without a
project:

- **`machine-token issue M`:** `MachineTokens.issue` as a verb. Idempotent: run again for a person already admitted,
  it answers the same token and changes nothing. It answers `{"machine":"M","token":"…"}` on stdout only and writes
  nothing beyond the token files the homeserver reads.
- **`machine-token revoke M`:** `MachineTokens.revoke`; answers `{"machine":"M","revoked":true|false}`. The
  homeserver refuses the token at its next registration.
- **`machine-token expel M`:** every account named `@….<M>` on the homeserver is taken out, `@porelo.<M>` first, and
  the homeserver is read again until none is left (as `dismiss` reads its rooms again). It is run before `revoke`,
  since a revoked token logs no account out.
  - The project admins' rooms are not the administrator's, so `dismiss`'s bans (which need power in each room) do not
    reach them. `expel` works server-wide through the admin room (`AdminRoom`): the accounts are listed, logged out
    and taken out of every room.
  - Answers `{"machine":"M","expelled":[…]}`.
- **The homeserver's administrator without a project:** today `@porelo` is registered by a project's `setup`. The three
  verbs make and keep it on first use, as `setup` does (`LocalHomeserver`, the kept token), so a host whose homeserver
  serves only central-mode users still has one.
- `describe` names none of them: they are the host admin's, not Porelo's.

Tests, red first: `MachineTokensTest` for the verbs' answers and idempotence; `LocalHomeserverIT` for a person issued a
token who registers `@porelo.<M>` and `@task.<M>` in central mode, is expelled and revoked, and can neither log in nor
register again, while another person's accounts stay.

## Acceptance

- On a homeserver with no project, `issue` twice answers one token, `revoke` makes a registration with it fail, and
  `expel` leaves no `@….<M>` that can log in or is a member of any room.
- A second person's accounts and token are unchanged through all of it, measured.
- Nothing of a token appears on stderr, in a file name or a log line.

## To be checked

- **Whether an expelled name can be used again.** Tuwunel's `users deactivate` keeps a deactivated name taken, so a
  person removed and later added again would not get `@porelo.<M>` back. If so, `expel` logs out, takes the account
  out of every room and resets its password to one nobody holds, which leaves the name free for the person's own
  Porelo to take back. Measured on Tuwunel 1.9.4 before building.
- Which admin-room command lists accounts by name on Tuwunel 1.9.4 (`users list-users` or similar), and its limit.
- Related: [MX38](README.md#mx38) (an application service per project) and the Idea of an exclusive namespace per
  person, which [PJ78](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj78) left out under
  the operator's QM46 (a).
