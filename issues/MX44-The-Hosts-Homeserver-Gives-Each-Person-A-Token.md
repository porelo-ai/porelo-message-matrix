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
  `M_UNKNOWN_TOKEN` at once.
- **`reset-password` ends no session:** the account's existing access token still answers `whoami`; only the old
  password stops logging in. So `expel` sets a password of its own, logs in with it and ends every session
  (`/logout/all`), takes the account out of every room (`list-joined-rooms`, `force-leave-room`), and sets a
  password nobody keeps last.

## To be checked

- **How a person removed and added again gets their name back.** A deactivated name stays taken, and an account
  locked with a password nobody holds cannot be logged into by the person's Porelo, whose passwords derive from its
  machine secret. Asked of the operator (QM48); `expel` waits on the answer, `issue` and `revoke` do not.
- Related: [MX38](README.md#mx38) (an application service per project) and the Idea of an exclusive namespace per
  person, which the operator left out under QM46 (a).
