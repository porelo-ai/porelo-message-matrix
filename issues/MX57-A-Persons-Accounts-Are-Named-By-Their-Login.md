# MX57 — A Person's Accounts Are Named By Their Login

**Status:** Accepted; blocked by [MX56](README.md#mx56) and [MX30](README.md#mx30).

**What must be true.** On a host's homeserver every account belongs to a person, whatever machine they sit at: the
person's own account is `@<login>:<server>`, and their Porelo's are `@porelo.<login>`, `@porelo-relay-<project>.<login>`
and `@<task>.<login>` on the same server. A person named in the project's access file is in the project's room, one
the access file drops is out of it at the next `members`, and a person purged from the host loses every account at
once.

## Why

On a central homeserver the accounts and the host's records are per machine today: `@….<machine>`,
`machine-token issue|revoke|expel <machine>`, and `members --machines`. A person now reaches the host with their own
key from any machine, and their Porelo, its relay and its tasks run once, in the person's own account on the host; a
second laptop is a second view, not a second runner. Nobody names a machine any more, so the host could not map a
person to their machines to take them out.

## The shape

The contract, in words:

- **`--login <login>`** takes the place of `--machine` on every verb, `PORELO_MATRIX_LOGIN` the place of
  `PORELO_MATRIX_MACHINE` among what Porelo keeps, `login` the place of `machine` in `setup`'s answer, and `login`
  the place of `machine` in what `describe` says the transport takes: the suffix of every account the transport makes
  on a central homeserver. A login is a name as the homeserver's gate takes it: no dot, and not one the transport
  keeps. Porelo makes task names
  unique per login, so the transport does not guard against two runners.
- **Accounts** on a host's homeserver (`unix:` or `*.localhost`) are registered through its gate, which takes only
  the caller's own login as the suffix and needs no token from the transport; on a homeserver elsewhere, with the
  registration token from `registration_token_file`, as today.
- **`members --project P --persons A,B,…`**, logins sorted, run by the room's maker: invites each person's `@<login>`
  and `@porelo.<login>` not yet in, and takes out every account of a login the list no longer names. `invited` and
  `removed` in its answer stay lists of full account ids. `--machines` goes.
- **`expel <login>`**, run by the host's helper as the homeserver's account: deactivates `@<login>` and every
  `@….<login>`, `@porelo.<login>` first, and records the login as expelled beside the homeserver's data. The
  homeserver keeps deactivated names taken, so a purged login is never made again on that host: the gate refuses
  every registration for an expelled login, and a person comes back under a new login. It answers
  `{"login": "…", "known": true|false, "expelled": [ … ]}`, with exit 0 when run again; a login of which nothing is
  known is left as it is.
- **A lock** deactivates nothing: the host drops the login from the gate's group, which refuses its next request.
  The project admins take the person out of the access file if they are to leave the room; then `members` does.
- **A lock for one project** is the access file's: Porelo leaves a person the access file locks for that project out of
  `--persons`, so `members` takes their accounts out of that project's rooms and leaves their other projects' rooms
  and their accounts as they are; let back in, the next `members` invites them again.

## Acceptance

- A project with two persons' logins in the access file: `members` brings both persons' `@<login>` and
  `@porelo.<login>` into the room; run again with the same list it changes nothing; run with one login dropped it
  takes out every account of that login and no other. Seen to fail: an account of a dropped login stays in the room.
- `expel <login>` leaves no account of that login able to sign in or stay in a room, touches no other login's
  account, and answers `known` with nothing left when run again. Seen to fail: `expel` deactivates another login's
  account; an expelled login registers again through the gate.
- No account the transport makes on a central homeserver ends in a machine's name, and `--machine`,
  `PORELO_MATRIX_MACHINE` and the key `machine` in `setup`'s and `describe`'s answers are gone. Seen to fail: a
  verb still takes `--machine`.
