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
  there a person runs a verb by hand, with the environment. `describe`, `room`, `homeserver-token`, `publish`,
  `backup` and `restore` read nothing there; `machine-token` and `expel` read only `secrets`.
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
  TCP listener ownership is not checked. An unreachable socket is temporary (75), as an unreachable TCP
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
| `setup --project P [--login L] [--loopback-only]` | On the account's own homeserver: starts the local homeserver (on a start timeout, the last stderr line gives the last `Error: ` summary, else the last `ERROR` message, else `the homeserver's unit is <state>`; after ANSI and control stripping, a whole message may contain only ASCII letters, digits, space and ``. , : ; ( ) [ ] { } ` ' " _ - / ! ?``, no run of at least 8 `[A-Za-z0-9_-]` characters containing both a letter and a digit, no credential words, and no 8-character window of a known secret (the whole secret when shorter) across composed stripping, whitespace removal, URL decoding, JSON unescaping and NFKC normalization, or it is withheld as `the homeserver's last error was withheld: it held a secret`; diagnostics including parsing and filtering take at most four seconds, each command has a three-second budget and a 1 MiB output cap with oversized reads discarded, messages over 4096 characters are skipped whole, and safe causes are limited to 300 characters). Even a secret-free line containing `token` is withheld to fail closed; the journal holds the full line. Registers `@porelo`, the administrator; makes the room `#porelo-<P>` (invite-only, unlisted) and the relay `@porelo-relay-<P>`. On a central homeserver (`--login L` required): registers `@porelo.<login>`; once `@porelo.<login>` is invited into `room` (by a person, or by `admit` before it registered, which the first `setup` already takes up), joins it and admits the relay `@porelo-relay-<P>.<login>`; a first `setup` that cannot be admitted answers not admitted, never losing the secret it made | `account` (on the account's own homeserver: the administrator's token; on a central one: `@porelo.<login>`'s token, the person's secret as `PORELO_MATRIX_MACHINE_SECRET`, and `PORELO_MATRIX_LOGIN`), `secrets` (the relay's environment, for `poll`; on a central homeserver with `PORELO_MATRIX_LOGIN`), `conversation` (the room id), `reaches` (a JSON array of `"host:port"`, e.g. `["127.0.0.1:8008"]`); on a central homeserver also `login` (`"L"`) and `admitted` (`false`, with `address` (`@porelo.<login>:<server>`) and `room` for Porelo to name, and no `secrets`, `conversation` or `reaches`, until `@porelo.<login>` is invited) |
| `enroll --project P --task T [--display N] [--login L] [--loopback-only]` | On the account's own homeserver, the account `@<T>` in the room; on a central homeserver, `@<T>.<login>`, invited by `@porelo.<login>`. Shown as the nickname `N` (1 to 64 characters, no control characters, `@` or `:`) or else as `T`; enrolled again with another, renamed, its id kept. A task account in another project's room is refused (78) and left as it is | `secrets` (the task's environment; on a central homeserver with `PORELO_MATRIX_LOGIN`), `address` (its user id) |
| `retire --project P --task T [--login L]` | On the account's own homeserver, deactivates `@<T>`; on a central homeserver, `@<T>.<login>` leaves the room and logs out every session, so the name can be used again. One in another project's room is refused (78) and left active | `{}` |
| `join --project P --person N [--reset] [--token] [--display N] [--login L] [--loopback-only]` | On the account's own homeserver: a person's account in the room, with a new password. An existing account is refused (77) without `--reset`, except one `part` took out of this project's room that is joined or invited in no project's room: it is taken back without `--reset`. An account that left by itself, such as a retired task's, never is. `--display` sets the display name with the same validation as `enroll`. `--token` logs out every earlier session, including on reset and when taking back a parted account, and keeps the password private. On a central homeserver: refused (77); people are managed at the homeserver itself | Without `--token`: `login` and `shown`; the password is printed once, and the admin-room command that set it and its reply are redacted at once. With `--token`: exactly `{"login":{"user":"@N:server","room":"!room-id"},"secrets":{"PORELO_MATRIX_ACCESS_TOKEN":"<token>"}}`; no password or `shown`, and no other secret |
| `part --project P --person N [--loopback-only]` | On the account's own homeserver: the administrator kicks a joined or invited person out of the project's room. In no other project's room, the account gets a fresh password nobody is shown and logs out every session through the client API; it is never deactivated, so `join` without `--reset` can take it back. In another project's room too, its password and sessions stay valid. No account or no membership: nothing to do. On a central homeserver: refused (77) | `{"removed":"@N:server"}` when locked; `{"kept":"@N:server","why":"a member of #porelo-Q:server too"}` when another project keeps it; `{}` when nothing was done; exit 0 in all three |
| `admit --project P --machine M [--loopback-only]` | On the account's own homeserver, published for another machine ([the homeserver](homeserver.md#for-another-machine-on-the-vpn)): gives the machine `M` a registration token of its own and invites `@porelo.<M>` into the project's room, before it is registered, so `M`'s own `setup` registers it and joins. Porelo names the admitted machine as `--machine` and adds none of its own. Run again, the same token; admitted again after `dismiss`, the bans `dismiss` made are lifted, and no other. **The name `.<M>` is the machine's alone on this homeserver while it is admitted:** `admit` is refused (78) for a machine whose name nests with an admitted one's (`sub.hostb` beside `hostb`, either way round), and for its first admission while a member of any project's room here is named `@<name>.<M>`; `setup`, `join` and `enroll` refuse (78) a project, person or task named `<name>.<M>` for an admitted `M`, and `clear --project` leaves an admitted machine's accounts as they are, naming them in `kept`. Refused (78) for an offline project and while nothing is published; on a central homeserver refused (77) | `{"admission":{"machine":"M","homeserver":"https://<published address>","room":"!room-id","ca_certificate":"<PEM>"},"secrets":{"registration_token":"<token>"}}`; the token only under `secrets` |
| `dismiss --project P --machine M` | On the account's own homeserver, for an admitted machine: every account of it is banned from every project's room here, `@porelo.<M>` first, then each member named `@<name>.<M>` (its relay, its tasks); the rooms are read again until none is left, since one not yet banned could invite another back, and after five rounds it stops with 75, to be run again. Then its registration token is refused from now on. A banned account is not one `part` took out, so `join` does not take it without `--reset`. A machine not admitted has nothing to dismiss; run again, it finds nothing. On a central homeserver refused (77) | `removed` (the token and each account) |
| `setup --project P --login L --create-room` | On a central homeserver, for the project admin's login: once `@porelo.<login>` is registered, makes the project's room `#porelo-<P>:<server>` with `@porelo.<login>` as its maker, invite-only, closed to guests and unlisted, as the account's own homeserver makes its room, or takes the room under that alias only if `@porelo.<login>` made it; any other room under the alias is refused (78) and nobody is put into it. The project's settings name that alias as `room`, else refused (78): a room of another name before any account is made, the alias on another server once the account shows the server's name. The first setup, whatever fails after the account is made, answers its account and `"admitted": false`, with `"created_room": true` when the room was made by then. Then as central `setup`: the relay admitted, the room answered. On the account's own homeserver refused (77) | as central `setup`, and `"created_room": true` |
| `members --project P --persons A,B,… [--login L]` | On a central homeserver, run as the room maker's `@porelo.<login>`: keeps the room to the persons listed, sorted by login, and run again with the same list changes nothing. Invites both `@<login>` and `@porelo.<login>` for each listed person not yet invited or joined, registered or not. Kicks every joined or invited account belonging to a login no longer listed: its bare account, provisioning account, relays and tasks, with every provisioning account removed first. The last dot separates the login from the task or relay name, so `bob` never owns `@task.x-bob`. The room is read again until none is left; after five rounds it stops with 75. The maker's login is kept even when absent from the list. An empty list (`--persons ""`) keeps only the maker's login. Each login follows the rule below, at most 500 distinct logins. A room under the alias the maker did not make is refused (78); a listed person's bare or provisioning account banned from the room is refused (78), before any invitations. On the account's own homeserver refused (77) | `{"invited":["@<login>:<server>","@porelo.<login>:<server>",…],"removed":["@….<login>:<server>",…]}` |
| `clear --project P [--login L]` | On the account's own homeserver: deactivates every account in the project's room (its tasks', its relay's, its people's) that is in no other project's room, deletes the room with its alias, and forgets the relay's poll position. On a central homeserver: this login's relay `@porelo-relay-<P>.<login>` and its tasks `@<T>.<login>` in the room leave the room and log out every session, so their names can be used again, and the relay's poll position is forgotten; `@porelo.<login>`, the room, people and other logins' accounts stay. A homeserver that no longer knows the login's token has let it go already: `clear` ends 0 and says so in `kept`. A homeserver not reached is 75. An account in another project's room too stays active there and is named in `kept` | `removed` (what went) and `kept` (each `what` and `why`) |
| `clear [--login L]` | The account's own homeserver once no project uses it: stops and disables it, removes its container and database, its port and registration token, and every poll position. The login is validated and changes nothing. Reads no settings | the same |
| `settings [--loopback-only]` | Checks the settings as a draft, with no network and no environment; with `--loopback-only`, for an offline project, a `homeserver` that is not loopback is one of the findings | `{"refused": […], "warnings": […]}` |

- **Settings:** `homeserver` (a central homeserver; requires `room`; `https`, loopback `http`, or
  `unix:<absolute path>`; a relative socket path is refused with 78), `room` (an alias `#local:server` or a room id,
  required with `homeserver` and refused without it), `ca_file` (absolute), `ca_certificate` (a CA's certificate as
  PEM text, as `admit` answers it; the transport keeps it as a file of its own, and names that file as
  `PORELO_MATRIX_CA_FILE` in the `secrets` it prints; refused with `ca_file` or with `tls_verify` off), `tls_verify` (`on`/`off`),
  `registration_token_file` (absolute, or `~/…` in the running account's home, so the signed settings can name one for every machine; only with `homeserver`; its first token is read, as the homeserver's own file holds this machine's first). Anything else is refused (78).
- **The printed `secrets` from `setup` and `enroll` name `PORELO_MATRIX_CA_FILE`, `PORELO_MATRIX_TLS_VERIFY` and
  `PORELO_MATRIX_LOGIN`** (empty on the account's own homeserver), so an inherited value never outweighs what the
  project configured.
- **Run again, each keeps what exists.** `part` twice is harmless: the second prints `{}`. `retire` twice
  is harmless, and so is `clear`: it says what it removed and what it could not, with why, exits 0,
  and the second time finds nothing. Porelo removes its own entries for the transport beside it.
  A project cleared can be set up again.
- **`--loopback-only`**, for an offline project: a `homeserver` that is not loopback, including a
  `unix:` socket, is refused (78) before any request.
- **The account's own homeserver by its published name:** a `homeserver` of `https://<name>:<port>` whose name is the
  one `publish --name` gave this account's homeserver is this machine's own, so the signed settings can name one
  homeserver for every machine. This machine then keeps its own homeserver's path, its administrator and its room, as
  with no `homeserver`; the port must be its homeserver's (78 otherwise), and `setup` refuses (78) a `room` that is
  not its room for the project. The name is taken without regard to case. Every other machine reaches it as a central homeserver.
- **A central homeserver** (`homeserver` set) requires `--login L`, or `PORELO_MATRIX_LOGIN` kept from
  `setup`; an option that differs from the kept login is refused with 78. A login begins with a lower-case
  letter, contains only lower-case letters, digits and `-`, has no dot, and is at most 32 characters.
  `porelo` and names starting with `porelo-` are reserved. Every other value is refused with 78, naming the rule.
  On a host's gate (`unix:` or `*.localhost`), registration needs no client-held registration token. Elsewhere the token
  comes from `registration_token_file`, readable by this account alone (78 otherwise), or from
  `PORELO_MATRIX_REGISTRATION_TOKEN`; both at once is refused (78). `PORELO_MATRIX_MACHINE_SECRET` keeps its
  existing key and derives passwords for the login's accounts.
- **The project's room is checked:** on the account's own homeserver it is taken only if it is Porelo's —
  made by its administrator, invite-only, guests forbidden — and a room under the alias that is anybody
  else's, or was opened since, is refused (78) by `setup`, `enroll`, `join`, `part`, `admit` and `dismiss`, while `clear`
  leaves it and its members alone. On a central homeserver the room named by `room` must be invite-only
  with guests forbidden; otherwise `setup` leaves it and refuses (78), and `enroll` refuses (78).
- **Never a second administrator or a taken machine name:** on the account's own homeserver, an `@porelo`
  Porelo holds no token for is refused (78), except by the first `setup` after a `restore`, which takes it
  back. If its token is refused and no `@porelo` exists, the homeserver was reset (78, saying so). On a
  central homeserver, a login's provisioning account with another password is refused (78).
- Project, task and local person names use lower-case letters, digits and `. _ = -`; a local person's
  name may not begin with `porelo`. Central account suffixes follow the stricter login rule above.
  The legacy `admit` and `dismiss` keep their own `--machine` argument and its ASCII case folding.

## Expelling a login

`expel <login>` is run by the host's helper as the homeserver's account, with the administrator's token
on stdin under `secrets.PORELO_MATRIX_ADMIN_TOKEN`. It is a host command, outside `describe`'s lifecycle.
It records a known login in `expelled-logins/<login>` beside `homeserver.conf` before deactivating
`@<login>:<server>` and every `@<name>.<login>:<server>`, `@porelo.<login>` first. Deactivation ends its
sessions and removes it from every room. The gate uses the record to refuse new registrations; a
returning person needs a new login because deactivated account names remain taken.

It prints `{"login":"…","known":true|false,"expelled":[…]}` with full account ids. Running it again exits 0,
with `known: true` and an empty `expelled` list once all accounts are gone. An unknown login, with no record
and no account, is left available and answers `known: false`. It needs and changes no machine registration
token. Accounts of other logins, including `@task.x-bob` when expelling `bob`, are never touched.

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
  name of the form `Agent <Word>` names that member when it occurs as a whole phrase, case-sensitive:
  `Agent Core` matches `(cc Agent Core)` and
  `Agent Smith, Agent Core`, and never `Agent Corey` or the body. A member listed in `m.mentions.user_ids`
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
| `backup <dir>` | Into an empty `<dir>`: the poll positions, then an online backup the running homeserver makes (asked by `SIGUSR2`), its backup directory as it stands, and `homeserver.conf`. A second `backup` waits for the first | `{"backup": <id>}` |
| `restore <dir>` | Before the first `setup`, with the unit stopped: puts a copy back - `homeserver.conf`, the backups, the poll positions - and has the homeserver restore the newest backup in a run of its own. Where the homeserver has a database already, it restores nothing and leaves it as it is | `{"restore": <id>}`, or `{"restore": "present"}` |

- **`backup`:** 0 done; 75 when the homeserver is not running or no backup appears within 60 s; 78 without the
  installed unit, or when `<dir>` is missing or not empty. A `backup` that fails leaves `<dir>` empty again.
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
| `describe` | Prints what the transport is, reading nothing: scheme `matrix`, `waitsOn: "the room's homeserver"` (one line, at most 80 characters), `poll`, `confirms: read`, `max_bytes: 10240`, `shown: true`, `persons: true`, `direct: true`, `waits: true`, `mention: true`, `display: true`, `takes: ["login", "create-room"]`, `secrets: "input"`, its credential and its lifecycle verbs |
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
- **A person's file** is `{"sender", "to": [...], "body", "eventId", "replyTo"?, "direct"?, "helpers"?, "at"}`.
  - **Its `to`** is whom it is meant for, as Matrix states it: the accounts the client marked as mentioned
    (`m.mentions`, else `matrix.to` links), plus the sender of the message it replies to, less the writer
    and the room's helpers (the administrator and the relay). It may be `[]`. Every task reads the room,
    so who reads it is Porelo's, not `to`'s.
  - **Its `helpers`** is present and `true` exactly when the line named only the room's helpers, with `to`
    then empty; otherwise it is absent, never `false`. Porelo then wakes no task and answers through `send`
    with the relay's token.
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
