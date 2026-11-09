# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed

- The local homeserver's container, and its run for `backup` and `restore`, open at most 65536 files
  (`--ulimit=nofile=65536:65536`) instead of whatever the host allows.
- A person's line in the room headed `Agent Pi: …`, typed as the agents write it, is meant for the member shown as
  Agent Pi: the person file's `to` names that account beside the client's mentions and the replied-to sender.
- A person's line in the room headed `All agents: …` is marked `"all": true` in its person file, meant for every task of
  the project.
- A person's line in the room that mentions the whole room (`@room`, `m.mentions.room`) is marked `"all": true` too.
- A head names a member by its whole display, `Agent Pi` or `Agent Pi (fix)`: `Agent Pi:` names only the member shown
  `Agent Pi`, never `Agent Pi (fix)`, and `Agent Pi (fix):` only the member shown so, for a person's line and the room
  tool alike. An agent's name may hold hyphens, as in `Agent Claude-Code`, read whole.
- `setup`, `backup` and `restore` say on stderr what they wait for when a wait starts, and how long they have waited
  every 10 s, through the same filter as the homeserver's last error; stdout keeps its one JSON answer. A wait names
  the time it has left, and its last line never more.
- `join` of a person whose account is already in another project's room on the account's own homeserver invites that
  account into this project's room instead of refusing it with 77: no password is set, no session ends, and the answer
  carries `"invited":true` and no password. With `--token` it is still refused, since a token needs the password.
- `backup` lets a snapshot an earlier backup asked for finish before it copies the positions, and copies a later one, so a
  late snapshot is not taken for its own; a target another backup filled in the meantime is refused with 78 and left as it is. The unit keeps 96 snapshots
  instead of 48, since each backup leaves two.
- A homeserver on a `unix:` socket or a `*.localhost` name no longer counts as a host's gate by its address: the
  homeserver's registration challenge tells. Where it asks only for a token and none was lent, `setup` and `enroll` stop
  with 78 naming `PORELO_MATRIX_REGISTRATION_TOKEN`; a token goes over a socket only to a listener of the running account,
  and a gate that offers the dummy stage never receives one. A socket's listener of the wrong account answers 78, not 75.
- A homeserver elsewhere's registration token reaches the transport only among the secrets on its input: `describe`
  asks Porelo to lend `PORELO_MATRIX_REGISTRATION_TOKEN` to `setup` and `enroll`, and the setting
  `registration_token_file` is refused like any unknown one.
- `setup`, `enroll` and `join` refuse the project `default` with 78, so a task of that project never joins a
  room, whatever settings reach the transport.
- A person's accounts on a central homeserver are named by their login (`--login`, `PORELO_MATRIX_LOGIN`), and
  `setup` answers `login`; `describe` lists `login` among `takes`. Logins have at most
  32 lower-case letters, digits and `-`, start with a letter, and exclude `porelo` and `porelo-` names.
- A host's gate handles registration without a client-held registration token; external homeservers keep
  the token flow.
- The host's gate hands an SSO login token only to Element Desktop's `element://vector/…` callback or to an `http`
  page at the forward the request came in on; any other `redirectUrl`, and a sign-in request with an `Origin`, is
  refused with 400.

### Added

- `members --project P [--login L]` says who is in the project's room: every joined or invited member with the name
  the room shows for it and whether it is a task (with the task's name, and its login on a central homeserver), a
  person, one of the room's helpers or something else, so the window can show who is in and which name reaches a task.
  It changes nothing; a missing room, a room the login left, or no setup yet answer an empty list.
- Behind the host's gate, the host's helper keeps a project's room as the administrator: `keep-room --project P
  --persons A,B,…` makes it and keeps it to the persons named, whether or not they have signed in, and `drop-room
  --project P` deletes it with its conversation. No client can make or take a project's alias through the gate.
- A host homeserver can run behind a Unix-socket gate that admits host logins, binds their Matrix accounts, handles registration and SSO, and protects project room aliases. `gate --prepare` keeps the administrator token private and configures the container without a reachable host TCP listener.
- The host's gate serves Element Desktop and other browser clients: it answers their CORS preflight, names an
  allowed origin in its own answers, refuses requests from a foreign origin, and passes sign-in discovery
  to the homeserver. Its sign-in list offers SSO and token login only.
- On a central homeserver, `poll` takes words as a person's, in the room and in a direct chat, only from a
  person's own account, a bare name with no dot, `send --to` reaches only one, and a central account's `secrets`
  name `PORELO_MATRIX_LOGIN`, which marks it so (empty on the account's own homeserver). Another person's tasks and relays no longer speak as a person.
- A person's file from `poll` names the person's login in `account` when the sender is a login's own account;
  from any other account it is absent.
- Central homeservers accept `unix:<absolute path>` for HTTP over a Unix socket; `*.localhost` names reach loopback without DNS and retain the listener ownership check.
- `expel <login>` deactivates a person's bare account and every account with that final login suffix,
  provisioning first, and records the login under `expelled-logins` for the host's gate. Unknown logins stay
  available, repeated calls exit 0, and other logins' accounts remain active. Behind the host's gate it reaches
  the homeserver through the gate's private socket, with the gate's administrator token among the secrets on stdin.
- Every verb that takes a token reads the secrets Porelo kept from stdin, as `{"settings": {...}, "secrets": {...}}`,
  and `describe` says so with `"secrets": "input"`. A token handed so is never in the transport's environment, which
  another process of the account could read in `/proc` in the moment before the transport makes itself unreadable.
  With secrets on stdin, no `PORELO_MATRIX_*` variable of the environment is read. A Porelo that still hands the
  bare settings on stdin and the secrets as the environment works as before.
- `settings` takes `--loopback-only` for an offline project.
- `join --token [--display <name>]` returns a vault token with the user and room id, keeps the password
  private, and logs out every earlier session of an existing account. A new account keeps its registration
  session. `join --display` uses the same validation as `enroll`.
- `room read [--all]`, `room post <file>`, and `room wait`, with `--entry <vault entry> --room <room id>`,
  let a person or agent converse on the local homeserver with a login read only from Porelo's vault.
  A line is meant for an agent its head names, by display name or pill, or for everybody (`All agents`,
  `@room`, or nobody named); an agent reads and wakes only for those, and they hold its post until read.
  Positions are per person and room, truncated timelines are paged so no line is lost, edits are not
  new lines, and a backup neither waits on nor refuses a room command. The login is read through the
  `porelo` the search path finds first, so an account's own install is the one asked.
- `part --project P --person N [--loopback-only]` removes a person from the project's room, keeping the
  account's password and sessions when another project uses it; otherwise it replaces the password and
  logs out every session without deactivating the account. `join` takes an account `part` locked back
  without `--reset`, but never a retired task's account. On a central homeserver, `part` is refused with 77.
- `poll` hands over no event the polling account sent itself, in the room as in a direct chat, so Porelo's
  answer through the relay never reaches the tasks.
- A person file carries `"helpers": true` with an empty `to` exactly when the line names only the room's
  helper accounts, so Porelo can answer through the relay without waking a task.
- `describe` names what a start waits on as `"waitsOn": "the room's homeserver"`.
- Several persons of one project share one conversation on a central homeserver (`homeserver` and `room` in the
  project's settings): `describe` announces `"takes": ["login"]`, every lifecycle verb takes `--login <login>`,
  and each person's Porelo registers only its own accounts (`@porelo.<login>`,
  `@porelo-relay-<project>.<login>`, `@<task>.<login>`) with passwords derived from a secret of its own
  (`PORELO_MATRIX_MACHINE_SECRET` in `account`), so no person's Porelo holds an administrator token or acts on another login's accounts.
- On a central homeserver, `setup` prints `"admitted": false` until a person in `room` invites `@porelo.<login>`,
  then joins the room (checking that it is invite-only and forbids guests) and admits its relay; `retire`
  and `clear --project` have only this login's accounts leave the room and log out every session, as themselves,
  so their names can be set up or enrolled again, and `join` answers 77 because people are managed at the
  homeserver itself.

- `backup <dir>` and `restore <dir>`, for a host's playbook: the local homeserver's conversation copied into a
  directory and put back on another machine, its rooms, messages and people's accounts as they were. The
  homeserver package keeps up to 48 online backups in `$XDG_STATE_HOME/porelo/matrix/backups`, made on
  `SIGUSR2`; a restored relay hands over only what it had not, and the first `setup` after a restore takes the
  administrator back, though Porelo's vault did not come with the copy. A `poll` while a backup copies the
  positions answers 75 at once, so a backup that hangs never hangs the polls.


### Security

- The host's gate lets only a page at the forward the request came in on, `http://` and its `Host`, or Element
  Desktop's `vector://vector` read its answers; a page on another local port or name, such as the forge's web
  through the same tunnel, is refused with 403 and can no longer register a name of the person's. An SSO sign-in whose
  `Sec-Fetch-Site` is other than `none` or `same-origin` is refused with 400 and makes no account.
- A token goes to a plain-`http` loopback homeserver only when the port is the account's own: another uid listening
  there - another account, while the account's own homeserver is down - gets 78 before any request.
- An account the transport takes back with a new password - one whose token Porelo lost, or one restored
  from a copy - has every other session logged out, so no token it had before stays valid.

- The running transport is not dumpable, so no other process of the account reads its tokens from `/proc`
  while it runs; a machine that refuses that gets 78.
- The transport reads JSON with jackson 2.22.3, which fixes the denial-of-service and deserialization advisories
  published against 2.22.1 at the end of September 2026.

### Changed

- Every `setup` sets the administrator's display name to `Porelo (administrator)` and the relay's to
  `Porelo (delivery)`, on new and existing rooms, including this machine's accounts on a central homeserver.
- When the local homeserver does not answer within the start limit, its last `Error: ` summary, else last
  `ERROR` message, else unit state explains why; after ANSI and control stripping, messages allow only ASCII
  letters, digits, space and ``. , : ; ( ) [ ] { } ` ' " _ - / ! ?``, reject runs of at least 8
  `[A-Za-z0-9_-]` characters containing both a letter and a digit, credential words and every 8-character
  window of known secrets (whole secrets when shorter) across composed decodings, and otherwise say
  `the homeserver's last error was withheld: it held a secret`;
  diagnostics including parsing and filtering take at most four seconds, each command has a three-second
  budget and a 1 MiB output cap with oversized reads discarded, messages over 4096 characters are skipped
  whole, and safe causes are limited to 300 characters. Diagnostic commands use their own session and process
  group when a root-owned, protected `/usr/bin/setsid` is available; cleanup kills the whole group and sampled
  descendants after normal completion or timeout.
- A `homeserver` in the project's settings is now always a central homeserver and needs `room` (a room alias or a
  room id; `room` without `homeserver` is refused too). A project on a homeserver elsewhere names its room as
  room id; `room` without `homeserver` is refused too). A project on a homeserver elsewhere names its room as
  `room`, invites each person's `@porelo.<login>` into it, and enrolls its tasks again; the earlier accounts stay
- The code follows the shared style of every Porelo repository: one `.editorconfig`, and `google-java-format`
  in its AOSP style checked by the build, which refuses code that does not match it. The build takes
  `porelo-parent` 0.2.2 and the shared tools 0.5.2, and its checkouts keep no token for later steps.
- `setup` names the scheme's port in `reaches` when the homeserver's URL names none, as the contract says.
- The homeserver package runs Tuwunel 1.9.4, pinned by its digest.
- The homeserver's unit reads its port and registration token under `$XDG_CONFIG_HOME` when it is set, where
  the transport writes them.

### Fixed

- Tasks of one project started at once on a central homeserver no longer end `setup` with 75 while the project's
  relay is being polled: setup waits for the relay's poll only when it must forget the relay's position.
- A connection the gate closes for idling no longer reads as a bad request, with an answer to nobody and an
  exception.
- A person's provisioning account invited into the room before it registered joins at its first `setup`,
  not only at the second. A central `setup` not yet admitted answers `address` and `room`, so Porelo names the account
  a person lets in and the room.
- `clear` on a central homeserver ends 75, saying the homeserver was not reached, where it cannot be reached; it
  answered 78 "not valid" before. A token the homeserver no longer knows means it let the login go already: `clear`
  then ends 0 and says so.
- A first `setup` beside a homeserver that already runs stops it before it chooses the port, so the homeserver keeps
  its port and comes back where the file says, instead of `setup` waiting three minutes on a port nothing listens on.
- A registration token changed while the homeserver runs is read at once. The registration token
  moved into `$XDG_CONFIG_HOME/porelo/matrix/tokens/`, and the unit mounts that directory: a mounted file kept the token
  file the container started with, so a new token got `401 Invalid registration token` until a restart. The
  unit's next start moves an existing token file there, and `setup` restarts
  a homeserver an earlier version started.
- A backup stops trying to acquire its locks when their shared wait budget expires, even when a
  holder releases a lock just after the deadline.
- A backup no longer starves behind Porelo's poll loops; it waits at most about the longest poll
  already running.
- The local homeserver no longer sizes itself from the machine's cores: its database pool is capped at 64
  workers, its read cache at 256 MB and RocksDB at 2 threads, and its container runs with `--pids-limit=512`,
  `--memory=1g` and `--cpus=2`, for backup and restore too. On 32 cores the pool had reached podman's pids limit and
  the homeserver did not start.
- A person in the conversation is shown by the name they joined with: the local homeserver no longer adds its
  default suffix, an emoji, to every new account's display name.
- `clear` for the account takes `--login`, as Porelo passes it, so `porelo vault clear` takes the account's
  homeserver away instead of failing with 64.
- `enroll` and `retire` refuse (78) a task account another project's room has, as two projects whose names run
  together give one task name; before, the second project's `enroll` took that task's account over and its token
  stopped working.
- `clear --project` leaves active an account that is in another project's room too - a person in two projects, or
  a task whose name both give - and names it in `kept`; before, it was deactivated and lost the other project.
- A person's message with a mention link that is no valid escape no longer stops every later poll of the room.
- `enroll`, `join`, `retire` and `clear` refuse the homeserver kept from `setup` in plain http on another host
  (78), and under `--loopback-only` one that is not loopback, before any request.
- An admin command takes as its reply only the one that answers it, so a password reset during another
  lifecycle verb no longer leaves its reply unredacted.
- A direct chat written into `m.direct` while another writer wrote it is written again, so its messages are read.
- `setup`'s wait for the local homeserver ends at its limit on a port that takes the connection and never
  answers.
- `tls_verify: off` in the project's settings is warned of on stderr at every start, as the environment's is.
- An empty registration-token file, left by a start stopped half-way, is made anew, and the token is written
  whole before it takes its name.
- `send` with a mention whose display name fills the event is sent without its pill, instead of ending with 1.
- A `ca_file` that is no path is refused with 78, instead of ending with 1.
- `clear --project` waits for the relay's running poll before it forgets its position, so the poll cannot write
  it back and the project set up again polls; past 45 s it keeps the position and says why.

## [0.5.0] - 2026-10-17

### Changed

- The transport is Porelo's: the packages `porelo-message-transport-matrix` and `porelo-matrix-homeserver`, the
  program under `/usr/libexec/porelo/transports/`, the settings `PORELO_MATRIX_*`, its directories under `porelo/`
  and the accounts and room `@porelo`, `@porelo-relay-<project>` and `#porelo-<project>`. It is installed afresh:
  nothing a machine kept for the earlier transport is read or kept, and a homeserver already provisioned by it is
  set up anew.
- Every run on GitHub is titled with its workflow's name, the branch or tag, and the commit's message; the
  workflow for the shared rules is called the Shared rules check.
- The build takes the snapshot of `porelo-parent`, and with it the snapshot of the shared build tools; a
  release of this repository waits until both are released and pinned.
- A waiting `poll` goes on waiting through an answer that brings nothing to hand over, such as a read receipt,
  so a message that comes just after one is handed over at once, not at Porelo's next look seconds later.

## [0.4.1] - 2026-10-08

### Changed

- Every change is checked with the shared release tools: no page or source cites an issue, and every page
  under `doc/` is in the site's navigation exactly once. A change to documents alone runs only these
  checks and the tests that read a document, not the build.
- A release refuses to build when anything it is built with is a snapshot: a dependency, the BOM's
  managed versions, a plugin or a plugin's dependency.
- Every change is checked for a `README.md` beside the `pom.xml`, saying what the transport is and is not.
- The build takes the shared parent pom as its parent, which holds what every repository of the project shares; it publishes
  nothing to Maven Central.

## [0.4.0] - 2026-10-05

Initial public version.
