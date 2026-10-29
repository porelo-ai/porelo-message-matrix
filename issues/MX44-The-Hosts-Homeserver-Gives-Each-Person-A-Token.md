# MX44 — The Host's Homeserver Gives Each Person A Token

**Status:** Implementing.
**Size:** M - three verbs on an account's own homeserver with no project, read in today's `MachineTokens` and
`AdminRoom`, the homeserver's administrator made without a project's `setup`, docs and an integration test; no
contract another repository reads beyond the verbs' answers.

**What must be true.** The host admin's helper gives every person on a prepared host a registration token of their own
for the host's shared homeserver, and takes it away again, without a project and without any room: a person taken off
the host has no account left that can log in or stay in a room, and no other person's token or account is touched.

## Plan

Read in `36adabe`. The host's homeserver is an account's own homeserver (`porelo-matrix`,
the unit porelo-host installs, published by
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

## Measured on Tuwunel 1.9.4

- **`!admin users list-users`** lists every local account by id, one per line in a code block, with no paging option
  and no limit seen; a deactivated account is no longer listed.
- **A deactivated name stays taken:** registering it again answers `M_USER_IN_USE`, and its access tokens answer
  `M_UNKNOWN_TOKEN` at once. A deactivated account is a member of no room any more.
- **`reset-password` ends no session:** the account's existing access token still answers `whoami`; only the old
  password stops logging in.

## Removal is final for the name

- **The administrator's token** comes on stdin among the secrets, as Porelo hands it to `setup`; the first `issue`,
  given none, registers `@porelo` before any machine has a token and answers its token under `account`.
- **`expel` deactivates every `@….<M>`**, which ends its sessions, and records the machine as expelled beside the
  tokens (`expelled-machines/<M>`, not in `machine-tokens`, whose every file is a token), so the record outlives
  the token. The record is written and the token revoked first, under the tokens' lock, so no `issue` runs between;
  the answer adds `"revoked": true|false`.
- **What the review added:** `issue` checks the name, the record and alike names before it registers `@porelo`, and
  again under the lock; it refuses a machine not admitted whose accounts the homeserver lists already. `expel`
  refuses alike names and a machine of which nothing is known (no token, no record, no account), and orders
  `@porelo.<M>` by its exact id. `AdminRoom.users` refuses an answer it cannot read whole: every line in the code
  block must be a user id, as many as it says it found, the administrator among them.
- **A later `issue M` for an expelled machine is refused** with `Exit.CONFIG` and the words "M was expelled from
  this homeserver; add the person under another user name", and nothing is written.
- Keeping the name on a re-admission is [MX53](README.md#mx53).
- Related: [MX38](README.md#mx38) (an application service per project) and the Idea of an exclusive namespace per
  person, which the operator left out under QM46 (a).
