# The transport

`porelo-message-transport-matrix` is a program Porelo runs on the host, over files. It carries a message the
filter let through into the project's room, and brings the room's messages back for Porelo to deliver. It
never reads a message: what may pass is the filter's, and who gets what is Porelo's. This page is the
contract, for whoever works on Porelo or on the transport.

## Configuration

On stdin, never from an argument. A Porelo whose transport's `describe` says `"secrets": "input"` hands every
verb it gives secrets or settings one JSON object on stdin and closes it:

    {"settings": {...}, "secrets": {...}}

- **`secrets`** is the whole map the transport printed under `secrets` and Porelo kept, key for key, those below
  that are no secrets included; none of them is then in the environment, which the kernel shows in
  `/proc/<pid>/environ` from the moment of `exec`, before the transport can make itself unreadable.
- **`settings`** comes to the lifecycle verbs and `settings`; a lifecycle verb without it has empty settings.
- **Read by** the lifecycle verbs, `settings`, `check`, `send`, `poll`, `read`, `receipt` and `rename`, to its end,
  at most 1 MiB, before the first step, so a stdin left open makes the verb wait. A terminal on stdin is not read:
  there a person runs a verb by hand, with the environment. `describe`, `room`, `homeserver-token`, `gate`,
  `backup` and `restore` read nothing there; `expel` reads only `secrets`.
- **No mixing:** with `secrets` on stdin, no `PORELO_MATRIX_*` variable of the environment is read, not even one
  stdin lacks. A key of `secrets` that does not start with `PORELO_MATRIX_`, a value that is not a string, a key
  twice, or another shape is refused (78; `check` answers 2), naming the key and never the value.
- **A Porelo of the old way** hands the bare settings on stdin and the secrets as the environment. That works as
  before: anything on stdin but an object with `settings` or `secrets` is the old way's, the bare settings for a
  lifecycle verb and ignored by the others, and leaves the environment as it is. An object with either beside any
  other key, or that does not parse strictly, is refused (78), with where it broke and never what.

The keys, on stdin or in the old way's environment:

| Variable | What |
|---|---|
| `PORELO_MATRIX_HOMESERVER` | The homeserver's URL: `https`, `http` only on this machine's loopback (78 otherwise), or `unix:<absolute path>` for a Unix socket |
| `PORELO_MATRIX_ACCESS_TOKEN` | The account the transport acts as: a task's, or for `poll` the project's relay |
| `PORELO_MATRIX_CA_FILE` | PEM certificates trusted besides the built-in authorities |
| `PORELO_MATRIX_TLS_VERIFY` | `on` (default) or `off`, for development only; `off` warns on stderr at every start |
| `PORELO_MATRIX_LOGIN` | The person's login on a central homeserver; empty or absent on the account's own. Set, `poll` and `send` take only a person's own account for a person |
| `XDG_STATE_HOME`, else `HOME` | From the environment, in either way: where `poll` keeps its position: `…/porelo/transports/matrix/`; people's room positions are under `room/` |

- **Unix sockets speak HTTP/1.1**, with `Host: localhost`; socket file permissions guard access, and
  TCP listener ownership is not checked. Only a registration token goes solely to a listener of the running account;
  a listener of another account then answers 78. An unreachable socket is temporary (75), as an unreachable TCP
  homeserver is. `setup` answers the socket's path in `reaches`.
- **Names ending in `.localhost` are loopback**, as are `localhost`, `127.0.0.1`, and `::1`.
  A `*.localhost` URL connects to `127.0.0.1` on the same port without DNS; plain HTTP checks that
  listener's owner before sending a token. A name such as `foo.localhost.example.org` is remote.
- **No redirect is followed** (76), so the token reaches no other server.
- **A plain-`http` loopback port another uid listens on is 78**, before any request, so no other account of the
  machine receives a token while the account's own homeserver is down.
- **A certificate that is not trusted is 78**, before any request. The built-in authorities are fixed
  when the executable is built: a CA added to the machine's store changes nothing, `PORELO_MATRIX_CA_FILE`
  does. A CA file together with `off` is refused.

## The lifecycle

Porelo runs these to make a project's conversation, and keeps what they print without reading it. Each
takes the project's settings (`mail.transports.matrix`) and the secrets Porelo kept on stdin, as
[Configuration](#configuration) says; each prints one JSON object.

| Verb | What it does | What it prints |
|---|---|---|
| `setup --project P [--login L] [--loopback-only]` | On the account's own homeserver: starts the local homeserver (on a start timeout, the last stderr line gives the last `Error: ` summary, else the last `ERROR` message, else `the homeserver's unit is <state>`; after ANSI and control stripping, a whole message may contain only ASCII letters, digits, space and ``. , : ; ( ) [ ] { } ` ' " _ - / ! ?``, no run of at least 8 `[A-Za-z0-9_-]` characters containing both a letter and a digit, no credential words, and no 8-character window of a known secret (the whole secret when shorter) across composed stripping, whitespace removal, URL decoding, JSON unescaping and NFKC normalization, or it is withheld as `the homeserver's last error was withheld: it held a secret`; diagnostics including parsing and filtering take at most four seconds, each command has a three-second budget and a 1 MiB output cap with oversized reads discarded, messages over 4096 characters are skipped whole, and safe causes are limited to 300 characters). Even a secret-free line containing `token` is withheld to fail closed; the journal holds the full line. Registers `@porelo`, the administrator; makes the room `#porelo-<P>` (invite-only, unlisted) and the relay `@porelo-relay-<P>`. On a central homeserver (`--login L` required): registers `@porelo.<login>`; once `@porelo.<login>` is invited into `room` (including before it registered, which the first `setup` already takes up), joins it and admits the relay `@porelo-relay-<P>.<login>`; a first `setup` that cannot be admitted answers not admitted, never losing the secret it made | `account` (on the account's own homeserver: the administrator's token; on a central one: `@porelo.<login>`'s token, the person's secret as `PORELO_MATRIX_MACHINE_SECRET`, and `PORELO_MATRIX_LOGIN`), `secrets` (the relay's environment, for `poll`; on a central homeserver with `PORELO_MATRIX_LOGIN`), `conversation` (the room id), `reaches` (a JSON array of `"host:port"`, e.g. `["127.0.0.1:8008"]`); on a central homeserver also `login` (`"L"`) and `admitted` (`false`, with `address` (`@porelo.<login>:<server>`) and `room` for Porelo to name, and no `secrets`, `conversation` or `reaches`, until `@porelo.<login>` is invited) |
| `enroll --project P --task T [--display N] [--login L] [--loopback-only]` | On the account's own homeserver, the account `@<T>` in the room; on a central homeserver, `@<T>.<login>`, invited by `@porelo.<login>`. Shown as the nickname `N` (1 to 64 characters, no control characters, `@` or `:`) or else as `T`; enrolled again with another, renamed, its id kept. A task account in another project's room is refused (78) and left as it is | `secrets` (the task's environment; on a central homeserver with `PORELO_MATRIX_LOGIN`), `address` (its user id) |
| `retire --project P --task T [--login L]` | On the account's own homeserver, deactivates `@<T>`; on a central homeserver, `@<T>.<login>` leaves the room and logs out every session, so the name can be used again. One in another project's room is refused (78) and left active | `{}` |
| `join --project P --person N [--reset] [--token] [--display N] [--login L] [--loopback-only]` | On the account's own homeserver: a person's account in the room, with a new password. An existing account joined or invited in another project's room is invited into this one by the administrator, once, keeping its password, its sessions and its display name; with `--token` it is refused (77), since a token needs the password. Any other existing account is refused (77) without `--reset`, except one `part` took out of this project's room that is joined or invited in no project's room: it is taken back without `--reset`. An account that left by itself, such as a retired task's, never is. `--display` sets the display name with the same validation as `enroll`. `--token` logs out every earlier session, including on reset and when taking back a parted account, and keeps the password private. On a central homeserver: refused (77); people are managed at the homeserver itself | Invited: `{"login":{"homeserver":…,"user":"@N:server","room":"#porelo-P:server","loopback":…},"invited":true,"shown":…}`, with no password; `shown` says to accept the invitation in the person's own client. Otherwise without `--token`: `login` and `shown`; the password is printed once, and the admin-room command that set it and its reply are redacted at once. With `--token`: exactly `{"login":{"user":"@N:server","room":"!room-id"},"secrets":{"PORELO_MATRIX_ACCESS_TOKEN":"<token>"}}`; no password or `shown`, and no other secret |
| `part --project P --person N [--loopback-only]` | On the account's own homeserver: the administrator kicks a joined or invited person out of the project's room. In no other project's room, the account gets a fresh password nobody is shown and logs out every session through the client API; it is never deactivated, so `join` without `--reset` can take it back. In another project's room too, its password and sessions stay valid. No account or no membership: nothing to do. On a central homeserver: refused (77) | `{"removed":"@N:server"}` when locked; `{"kept":"@N:server","why":"a member of #porelo-Q:server too"}` when another project keeps it; `{}` when nothing was done; exit 0 in all three |
| `members --project P [--login L] [--loopback-only]` | Says who is in the project's room, and changes nothing: no join, leave, invite, kick or profile write, no homeserver started, no file written, no session logged in or out. On the account's own homeserver the room is `#porelo-P:server`, read as its administrator; on a central homeserver the configured room, read as the login, which must be invite-only with guests forbidden and hold this project's relay `@porelo-relay-P.<login>` joined, else 78. Only members joined or invited count, each once. Each `kind` is read from the id alone, helpers first: `@porelo`, `@porelo.<login>` and `@porelo-relay-…` on the room's server are `helper`; `@porelo-P-<task>` (own homeserver) or `@porelo-P-<task>.<login>` (central) a `task`; on a central homeserver a person as a login's person reads one, on the account's own any other name not starting `porelo`; a task's or a local person's name outside the name rule below, or another server's after the id's first `:`, is `other`, and an id outside Matrix's syntax (printable ASCII, at most 255 bytes) is left out. `shown` is the member's display name in the room with control and format characters removed, cut to 64 code points, or the id when nothing is left. A missing room, on a central homeserver a room the login is not in, and no secrets from `setup` yet answer an empty list | `{"room":"<alias>","members":[{"id":"@…","shown":"…","state":"join"|"invite","kind":"task"|"person"|"helper"|"other"}]}`, ordered task, person, helper, other, then by `shown` and `id`; a `task` also carries `"task":"<the name enroll was given>"`, and on a central homeserver `"login":"<login>"`. Porelo maps a member to its task by `task`, never by `shown`, which can change and repeat. Before `setup` on the account's own homeserver, where no room has a name yet: `{"members":[]}`; on a central homeserver the login is checked first (78 as for every verb) and the configured room is named; a homeserver naming no room for an alias is 76; exit 0 in all the others |
| `clear --project P [--login L]` | On the account's own homeserver: deactivates every account in the project's room (its tasks', its relay's, its people's) that is in no other project's room, deletes the room with its alias, and forgets the relay's poll position. On a central homeserver: this login's relay `@porelo-relay-<P>.<login>` and its tasks `@<T>.<login>` in the room leave the room and log out every session, so their names can be used again, and the relay's poll position is forgotten; `@porelo.<login>`, the room, people and other logins' accounts stay. A homeserver that no longer knows the login's token has let it go already: `clear` ends 0 and says so in `kept`. A homeserver not reached is 75. An account in another project's room too stays active there and is named in `kept` | `removed` (what went) and `kept` (each `what` and `why`) |
| `clear [--login L]` | The account's own homeserver once no project uses it: stops and disables it, removes its container, database and backups, its port, registration token and environment file, every poll and room position, the expelled logins, the gate's
administrator token and login tokens, and the gate's socket directory. The login is validated and changes nothing. Reads no settings | the same |
| `settings [--loopback-only]` | Checks the settings as a draft, with no network and no environment; with `--loopback-only`, for an offline project, a `homeserver` that is not loopback is one of the findings | `{"refused": […], "warnings": […]}` |

- **Settings:** `homeserver` (a central homeserver; requires `room`; `https`, loopback `http`, or
  `unix:<absolute path>`; a relative socket path is refused with 78), `room` (an alias `#local:server` or a room id,
  required with `homeserver` and refused without it), `ca_file` (absolute, or `~/…` in the running account's home),
  `tls_verify` (`on`/`off`). `ca_certificate` and anything else is refused (78).
- **The printed `secrets` from `setup` and `enroll` name `PORELO_MATRIX_CA_FILE`, `PORELO_MATRIX_TLS_VERIFY` and
  `PORELO_MATRIX_LOGIN`** (empty on the account's own homeserver), so an inherited value never outweighs what the
  project configured.
- **The project `default` has no room:** `setup`, `enroll`, `join` and `members` with `--project default` are refused
  (78) before any settings are read or any request is made, whatever the settings name. `retire`, `part` and `clear`
  stay open, so a leftover can still go.
- **Run again, each keeps what exists.** `part` twice is harmless: the second prints `{}`. `retire` twice
  is harmless, and so is `clear`: it says what it removed and what it could not, with why, exits 0,
  and the second time finds nothing. Porelo removes its own entries for the transport beside it.
  A project cleared can be set up again.
- **`--loopback-only`**, for an offline project: a `homeserver` that is not loopback, including a
  `unix:` socket, is refused (78) before any request.
- **A central homeserver** (`homeserver` set) requires `--login L`, or `PORELO_MATRIX_LOGIN` kept from
  `setup`; an option that differs from the kept login is refused with 78. A login begins with a lower-case
  letter, contains only lower-case letters, digits and `-`, has no dot, and is at most 32 characters.
  `porelo` and names starting with `porelo-` are reserved. Every other value is refused with 78, naming the rule.
  On a `unix:` or `*.localhost` homeserver nothing is asked beforehand and the homeserver's challenge decides: a host's gate
  offers the dummy stage and never receives a token; one that asks only for a token and got none stops `setup` and `enroll`
  with 78 naming it, and over a socket the token goes only to a listener of the running account (78 otherwise). Elsewhere the token is
  `PORELO_MATRIX_REGISTRATION_TOKEN` among the secrets on the input, which Porelo lends `setup` and `enroll` from the
  person's vault as `describe`'s `credentials` ask (78 without it). `PORELO_MATRIX_MACHINE_SECRET` keeps its
  existing key and derives passwords for the login's accounts.
- **The project's room is checked:** on the account's own homeserver it is taken only if it is Porelo's —
  made by its administrator, invite-only, guests forbidden — and a room under the alias that is anybody
  else's, or was opened since, is refused (78) by `setup`, `enroll`, `join`, `part` and `members`, while `clear`
  leaves it and its members alone. On a central homeserver the room named by `room` must be invite-only
  with guests forbidden; otherwise `setup` leaves it and refuses (78), and `enroll` refuses (78).
- **Never a second administrator or a taken machine name:** on the account's own homeserver, an `@porelo`
  Porelo holds no token for is refused (78), except by the first `setup` after a `restore`, which takes it
  back. If its token is refused and no `@porelo` exists, the homeserver was reset (78, saying so). On a
  central homeserver, a login's provisioning account with another password is refused (78).
- Project, task and local person names use lower-case letters, digits and `. _ = -`; a local person's
  name may not begin with `porelo`. Central account suffixes follow the stricter login rule above.

## Expelling a login

`expel <login>` is run by the host's helper as the homeserver's account, with the administrator's token
on stdin under `secrets.PORELO_MATRIX_ADMIN_TOKEN`. It is a host command, outside `describe`'s lifecycle.
It records a known login in `expelled-logins/<login>` beside `homeserver.conf` before deactivating
`@<login>:<server>` and every `@<name>.<login>:<server>`, `@porelo.<login>` first. Deactivation ends its
sessions and removes it from every room. The gate uses the record to refuse new registrations; a
returning person needs a new login because deactivated account names remain taken.

It prints `{"login":"…","known":true|false,"expelled":[…]}` with full account ids. Running it again exits 0,
with `known: true` and an empty `expelled` list once all accounts are gone. An unknown login, with no record
and no account, is left available and answers `known: false`. It needs and changes no registration
token. Accounts of other logins, including `@task.x-bob` when expelling `bob`, are never touched.

## Keeping a project's room behind the gate

The host's helper keeps a project's room, as the homeserver's account, with the gate's administrator token on stdin
under `secrets.PORELO_MATRIX_ADMIN_TOKEN` and the homeserver reached only through its private socket. These are host
commands, outside `describe`'s lifecycle. Where no gate is prepared they are refused (77) and start nothing; the token
missing or not the administrator's is 78; a room under the alias the administrator did not make is refused (78) and
left as it is. Each ends 75 when the homeserver is not reached or five rounds leave someone in, so the helper tries
again at its next follow.

- **`keep-room --project P --persons A,B,…`** makes `#porelo-<P>:<server>` if it is missing, with the administrator as
  its maker, invite-only, closed to guests and unlisted, then keeps it to the list: it invites `@<login>` and
  `@porelo.<login>` of each login named, signed in or not, and takes every account of a login no longer named out of
  this room, `@porelo.<login>` first. Run again with the same list it changes nothing. `--persons ""` is a project with
  no members: its room stays. At most 500 logins; the project `default` is refused (78). A kick is no ban, so a login
  named again is invited again. It answers
  `{"room":"#porelo-P:server","conversation":"!…","created_room":true|false,"invited":[…],"removed":[…]}`.
- **A person shows under the name the host passes**, given on the input beside the secrets, never on the command line,
  where a comma or `=` in a name could split into another login's entry:
  `{"settings":{"names":{"<login>":"<name>"}},"secrets":{…}}`. A key that is no login of `--persons`, another setting,
  names of another shape, or settings that are not one JSON object of at most 1 MiB are refused (78); a name breaking
  `enroll --display`'s rule (1 to 64 characters, no control or format characters, `@` or `:`) is refused (64), saying
  whose and not repeating it. Both before anything is looked at. After the ban check and before the invitations, each
  named login's account is given its name where it shows none (no display name, or an empty one), and a named login with
  no account yet is made with its name, under the gate's lock, so it never exists unnamed and a sign-in at that moment
  still gets its token. A name the account already shows - the person's own choice, their bare login too - stays, on
  every run. A login the gate expelled, also while the run waited for that lock, is neither made nor named. A homeserver
  busy (429, 5xx) is 75, one refusing the token (401, 403) 77, an unclear answer 76, each before that login is written.
  A login with no name is not made, as before. The answer is the same.
- **A person taken out reads nothing said after it**, and still what was said up to it: a Matrix homeserver answers a
  former member's history to the moment they left. `removed` in the answer names who went.
- **A person taken out comes back through Porelo.** Their relay's `poll` answers 78 and their tasks are out until
  Porelo runs `setup` and `enroll` again; `setup` then forgets a relay position lying only in rooms the relay has left, so
  a room that replaced a deleted one is read from its start.
- **`drop-room --project P`** takes every member out and deletes the room with its alias and conversation. Nobody's
  account is deactivated. No such room: nothing to do, exit 0. It answers
  `{"room":"#porelo-P:server","deleted":true|false,"removed":[…]}`.

## Talking in the room

For a person or an agent working on the host, the executable has a `room` group. Porelo does not run it,
and `describe` does not list it. Each command takes `--entry <vault entry> --room <room id>`; a room alias
or a user id is refused. It acts only in that room on the account's own homeserver.

| Command | What it does |
|---|---|
| `room read [--all] --entry <entry> --room <!room-id>` | Prints new lines meant for this account, or with `--all` every other member's text, then moves the position past everything. Each line is `## <UTC yyyy-MM-ddTHH:mmZ> — <display name>`, a newline, the body, and a blank line. The first read establishes a position and prints no history |
| `room post <file> --entry <entry> --room <!room-id>` | Syncs without saving. Refuses with 75 while any unread line is meant for this account, naming the count; another agent's lines never hold a post. Otherwise sends the file's exact UTF-8 text as one `m.text` event. A retry from the same position with the same text is the same event; a later read allows the same text to be posted again |
| `room wait --entry <entry> --room <!room-id>` | Syncs with a 30-second timeout per request, keeping its progress in memory. Other agents' lines and empty wakes keep waiting. Returns 0 with the headings of the meant-for lines when they arrive, leaving the saved position untouched |

- **Credentials come only from the vault:** the command runs `porelo vault credential --entry <entry>
  get` with the `porelo` its search path finds first, so an account's own install is the one asked, writes `protocol=http\nhost=127.0.0.1:<port>\n\n` to its stdin, and reads the `password=`
  field over its stdout pipe. The answer ends at its first empty line or EOF and is accepted only after
  the helper exits successfully; a descendant retaining a pipe cannot invalidate a completed answer.
  The token stays in memory. Up to 64 KiB of the helper's stderr passes
  through unchanged, with excess discarded; inherited pipes cannot hold the caller past its deadline.
  A failed or malformed answer is 78, naming only the entry. An early exit that breaks the request pipe
  still passes on the helper's stderr before failing. Its stdout and parser snippets never appear
  in a diagnostic. Any setting of `PORELO_MATRIX_ACCESS_TOKEN`, `PORELO_MATRIX_ADMIN_TOKEN`,
  `PORELO_MATRIX_REGISTRATION_TOKEN`, or `PORELO_MATRIX_MACHINE_SECRET`, even empty, is refused with 78.
- **The homeserver is fixed:** `http://127.0.0.1:<port>`, read from the account's `homeserver.conf`.
  The listener must belong to this account before any token is sent. Environment homeserver and TLS
  settings do not select another destination.
- **Whom a line addresses:** its head is the first line before its first `:`, when that colon is within
  the first 300 code points; without such a colon the line is prose with no head. A member's display
  name of the form `Agent <Word>` or `Agent <Word> (<short>)` names that member when the head holds that
  whole form as a phrase, case-sensitive, read as the longest such form at each place; `<Word>` is letters, numbers
  and `_`, in parts joined by single hyphens (`Agent Claude-Code`), `<short>` letters, numbers, `_`, `.` and `-`, and a parenthesis holding anything else leaves the bare name:
  `Agent Core` matches `(cc Agent Core)` and
  `Agent Smith, Agent Core`, and never `Agent Corey`, `Agent Core (fix)` or the body; `Agent Pi (fix)`
  names only the member shown so, and a short name no member shows names nobody. A member listed in `m.mentions.user_ids`
  is named too. The writer never counts as named: a signature or self-mention alone names nobody else.
  A head containing the whole phrase `All agents` in any case, `m.mentions.room: true`, or a line naming
  nobody is for everybody. An account's own lines never address it. Posts add the head's named members,
  excluding the writer, to `m.mentions.user_ids`, and `room: true` for `All agents`.
- **Only text in the named room counts:** events must be `m.room.message` with `msgtype: m.text`.
  Edits are skipped. A message without an event id is a protocol failure (76), leaving the position
  untouched. Truncated timelines are paged back before reading or counting unread lines.
- **One position per person and room** lives under `…/porelo/transports/matrix/room/`, keyed by the
  homeserver, user id and room id. A position is rewritten atomically after a read succeeds; `wait` and
  `post` require a first read (78 otherwise). A position belonging to an earlier database is refused
  with 78. Clearing the account's homeserver removes these positions with the poll positions.
  Room positions are independent of backups: room read and post continue during a backup, and a
  restored account with no room position starts fresh on its first read.
- **A post must fit whole:** invalid UTF-8 or serialized content over 64,512 bytes, including JSON
  escaping and mentions, is refused with 65. It is never cut.

## Keeping the conversation

For a host's playbook, so the account's own homeserver outlives the machine; Porelo never runs these, and
`describe` does not list them. Each takes an absolute directory and prints one JSON object.

| Command | What it does | What it prints |
|---|---|---|
| `backup <dir>` | Into an empty `<dir>`: under its locks, an online backup the running homeserver makes (asked by `SIGUSR2`) to let an earlier one finish, the poll positions, a second such backup, its backup directory up to that one, and `homeserver.conf`. A second `backup` waits for the first | `{"backup": <id>}` |
| `restore <dir>` | Before the first `setup`, with the unit stopped: puts a copy back - `homeserver.conf`, the backups, the poll positions - and has the homeserver restore the newest backup in a run of its own. Where the homeserver has a database already, it restores nothing and leaves it as it is | `{"restore": <id>}`, or `{"restore": "present"}` |

- **`backup`:** 0 done; 75 when the homeserver is not running, a lock stays held for its budget, or the two backups do not
  appear within 60 s; 78 without the installed unit, or when `<dir>` is missing or not empty, also when another `backup` filled
  it in the meantime, which is left as it is. Otherwise a `backup` that fails after finding `<dir>` empty under its locks leaves it empty again.
- **`restore`:** 0 restored or present; 78 when the unit runs without its database, when `<dir>` holds no whole
  backup, a link, or anything but positions among the positions, when this account's `homeserver.conf` names another port or server name, or when backups or
  poll positions of another database are here. A restore that fails takes away what it made.
- **No message is handed over twice:** the positions are copied before the backup and no poll moves while
  `backup` runs, so a restored relay hands over exactly what came after its last poll. What was sent after the
  backup is not in the copy.
- **The first `setup` after a restore** finds `@porelo` with no token in Porelo's vault, which is not copied, and
  takes it back: a new password set in a run of the homeserver's own, its other sessions logged out. Only behind
  the mark `restore` leaves, and only on the account's own homeserver; anywhere else the refusal stays. The relay
  and each task enrolled again are taken back the same way, so no token restored with the copy stays valid.

## Messages

| Verb | What it does |
|---|---|
| `describe` | Prints what the transport is, reading nothing: scheme `matrix`, `waitsOn: "the room's homeserver"` (one line, at most 80 characters), `poll`, `confirms: read`, `max_bytes: 10240`, `shown: true`, `persons: true`, `direct: true`, `waits: true`, `mention: true`, `display: true`, `takes: ["login"]`, `secrets: "input"`, its credentials, the registration token lent to `setup` and `enroll`, and its lifecycle verbs |
| `check` | Whether it could work now: the configuration complete, the homeserver answering, the token accepted. 0 usable, 2 not |
| `send <file> <sig> --to <room id \| @user:server> [--shown <line>] [--mention @user:server]...` | To a person's user id: into the acting account's direct chat with them, opened (invite-only, marked direct) if there is none. One `m.text` event: the message in `org.fuin.porelo.message`, the signature in `org.fuin.porelo.signature`, and as its `body`, what a client shows, the line `--shown` names. In a room, each `--mention` is named in `m.mentions` and shown as a pill, by the name that person's or task's account shows, before the line, so that person's client highlights it; without one, `m.mentions` is empty and the message notifies nobody. In a direct chat nobody is mentioned. Prints `{"reference":"<event id>"}`. A retry is the same event |
| `rename --display <nickname>` | Run with a task's token: the name its account shows in the room and its direct chats becomes the nickname. Its id, its token and every address stay. Prints nothing |
| `poll --into <dir> [--persons \| --direct] [--wait <s>]` | Writes every new Porelo message as `<time>--matrix-<uuid>.json` with its `.sig` and its `.sender` (the account that sent it, as the homeserver says), each file whole, from `org.fuin.porelo.message`. With `--persons`, a person's text in the room too, as `<time>--matrix-person-<uuid>.json`, no `.sig`. With `--direct`, run with a task's token: only its direct chats with people, as person files marked `direct`. With `--wait` (1 to 30 s), the homeserver holds the sync open and it answers as soon as a message to hand over arrives; an answer with nothing in it, such as a read receipt the homeserver woke for, does not end the wait. An event the polling account sent itself is never handed over, in the room as in a direct chat: it writes there only to answer a person for Porelo. Anything else is skipped and named on stderr |
| `receipt <reference> --by <account>` | `{"state":"read","at":…}`, `{"state":"delivered"}` or `{"state":"unknown"}` |
| `read <reference>` | Posts the account's read receipt, so the sender's `receipt` answers `read`. The reference is the event id or the file name `poll` gave, less `.json` |

- **A message over `max_bytes` is refused** (65), whatever it holds; so is one that is not UTF-8.
- **The line is Porelo's**, made on the host from the checked message: sender, addressee and text. The
  transport carries it unread, and cuts it where the event would not fit, ending "… (cut; N characters in
  all)"; the message is never cut. Without `--shown` the body is "A Porelo message.", never the message.
- **An event without `org.fuin.porelo.message`**, the message in its body as before, is not delivered.
- **A person's file** is `{"sender", "account"?, "to": [...], "body", "eventId", "replyTo"?, "direct"?, "helpers"?,
  "all"?, "at"}`.
  - **Its `account`** is the person's login, present exactly when `sender` is a login's own account: a bare name,
    with no dot, on the polling account's homeserver, never `porelo` or `porelo-…`. On a host's homeserver only that
    login's gate makes it. From any other account it is absent, never guessed: absent means no known person.
  - **Its `to`** is whom it is meant for, as the event states it: the accounts the client marked as mentioned
    (`m.mentions`, else `matrix.to` links), plus the sender of the message it replies to, plus each member
    shown as `Agent <Word>` or `Agent <Word> (<short>)` whom the first line's head names by the room tool's rule
    (above, "Talking in the room"), less the writer
    and the room's helpers (the administrator and the relay). The members' names are asked once per room and
    sync, and only for a head that holds such a display. It may be `[]`. Every task reads the room,
    so who reads it is Porelo's, not `to`'s.
  - **Its `helpers`** is present and `true` exactly when the line named only the room's helpers, with `to`
    then empty, and is not for all agents; otherwise it is absent, never `false`. Porelo then wakes no task and
    answers through `send` with the relay's token.
  - **Its `all`** is present and `true` exactly when the room line's head says `All agents` by the room tool's rule
    (the whole phrase, in any case, before the first colon on the first line, within 300 code points), or when the
    person's client mentioned the whole room (`m.mentions.room` is the JSON `true`, as for `@room`); otherwise it is
    absent, never `false`. Porelo then hands it to every task of the project. Deciding it asks the homeserver nothing,
    though `to` may still ask for the members' names; a direct chat is never marked, and reaches only its task.
  - **In a direct chat**, `to` is the task's own account and `direct` is `true`.
  - **Not handed over:** notices, edits, and anything not text.
- **On a central homeserver only a person's own account is a person**: a bare name, with no dot, on the
  homeserver the polling account is on, never `porelo` or `porelo-…`; on a host that is the person's login. The
  secrets of a central account name `PORELO_MATRIX_LOGIN`, which marks it so. Text from any other account in the
  room, another person's task or relay or the administrator, is skipped and named on stderr; its invitation to a
  direct chat is declined; and `send --to` it is refused (78), into a chat open already too. A central account
  set up or enrolled before its secrets named the login is enrolled again to be marked. On the account's own
  homeserver every account in the room is this Porelo's or a person it joined, and nothing of this applies.
- **A direct chat is taken only from somebody who shares a room with the task's account**, the project's
  room. Any other invitation is declined and named on stderr. Which rooms are direct chats is the
  account's `m.direct`, as for every client.
- **An encrypted chat is declined, with a reason the person's client shows**: a task holds no keys and
  could never read it. nheko and Element encrypt a chat they open by default, so the person opens it
  again with encryption off. An encrypted message in any room is named on stderr, never lost silently.
- **`poll` saves its position only after the files are written**: a crash delivers a message twice,
  never skips one. One poll per account at a time (75 for a second), and none while `backup` copies the
  positions (75, never a wait).
- **A position from an earlier database is refused** (78): `poll` keeps the rooms beside its position
  and stops when the account is in none of them.

## Exit codes

`check` answers 0 or 2. For the other verbs, 0 is done and 75 is temporary - Porelo keeps the message and
tries again; every other code refuses it.

| Code | Meaning |
|---|---|
| 0 | Done |
| 64 | Arguments not in the contract's shape |
| 65 | The message cannot be carried: not UTF-8, or too large |
| 75 | Temporary: the homeserver unreachable or busy, another poll or room read/post running, a backup copying the positions, or unread addressed room lines holding a post |
| 76 | An answer the transport does not understand, or a redirect |
| 77 | Refused by the homeserver: the token, or a room the account is not in |
| 78 | The configuration: missing, unknown, an untrusted certificate, a reset homeserver, a loopback port another uid listens on; or a machine that will not keep the tokens from the account's other processes |

The reason goes to stderr, and never contains a token.
