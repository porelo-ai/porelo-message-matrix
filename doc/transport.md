# The transport

`porelo-message-transport-matrix` is a program Porelo runs on the host, over files. It carries a message the
filter let through into the project's room, and brings the room's messages back for Porelo to deliver. It
never reads a message: what may pass is the filter's, and who gets what is Porelo's. This page is the
contract, for whoever works on Porelo or on the transport.

## Configuration

From the environment, never from an argument:

| Variable | What |
|---|---|
| `PORELO_MATRIX_HOMESERVER` | The homeserver's URL: `https`, or `http` only on this machine's loopback (78 otherwise) |
| `PORELO_MATRIX_ACCESS_TOKEN` | The account the transport acts as: a task's, or for `poll` the project's relay |
| `PORELO_MATRIX_CA_FILE` | PEM certificates trusted besides the built-in authorities |
| `PORELO_MATRIX_TLS_VERIFY` | `on` (default) or `off`, for development only; `off` warns on stderr at every start |
| `XDG_STATE_HOME`, else `HOME` | Where `poll` keeps its position: `…/porelo/transports/matrix/`; people's room positions are under `room/` |

- **No redirect is followed** (76), so the token reaches no other server.
- **A plain-`http` loopback port another uid listens on is 78**, before any request, so no other account of the
  machine receives a token while the account's own homeserver is down.
- **A certificate that is not trusted is 78**, before any request. The built-in authorities are fixed
  when the executable is built: a CA added to the machine's store changes nothing, `PORELO_MATRIX_CA_FILE`
  does. A CA file together with `off` is refused.

## The lifecycle

Porelo runs these to make a project's conversation, and keeps what they print without reading it. Each
takes the project's settings (`mail.transports.matrix`) as JSON on stdin, and the secrets Porelo kept as
its environment; each prints one JSON object.

| Verb | What it does | What it prints |
|---|---|---|
| `setup --project P [--machine M] [--loopback-only]` | On the account's own homeserver: starts the local homeserver (on a start timeout, the last stderr line gives the last `Error: ` summary, else the last `ERROR` message, else `the homeserver's unit is <state>`; after ANSI and control stripping, a whole message may contain only ASCII letters, digits, space and ``. , : ; ( ) [ ] { } ` ' " _ - / ! ?``, no run of at least 8 `[A-Za-z0-9_-]` characters containing both a letter and a digit, no credential words, and no 8-character window of a known secret (the whole secret when shorter) across composed stripping, whitespace removal, URL decoding, JSON unescaping and NFKC normalization, or it is withheld as `the homeserver's last error was withheld: it held a secret`; diagnostics including parsing and filtering take at most four seconds, each command has a three-second budget and a 1 MiB output cap with oversized reads discarded, messages over 4096 characters are skipped whole, and safe causes are limited to 300 characters). Even a secret-free line containing `token` is withheld to fail closed; the journal holds the full line. Registers `@porelo`, the administrator; makes the room `#porelo-<P>` (invite-only, unlisted) and the relay `@porelo-relay-<P>`. On a central homeserver (`--machine M` required): registers `@porelo.<M>`; once a person has invited `@porelo.<M>` into `room`, joins it and admits the relay `@porelo-relay-<P>.<M>` | `account` (on the account's own homeserver: the administrator's token; on a central one: `@porelo.<M>`'s token, the machine's secret as `PORELO_MATRIX_MACHINE_SECRET`, and `PORELO_MATRIX_MACHINE`), `secrets` (the relay's environment, for `poll`), `conversation` (the room id), `reaches` (a JSON array of `"host:port"`, e.g. `["127.0.0.1:8008"]`); on a central homeserver also `machine` (`"M"`) and `admitted` (`false`, with no `secrets`, `conversation` or `reaches`, until a person invites `@porelo.<M>`) |
| `enroll --project P --task T [--display N] [--machine M] [--loopback-only]` | On the account's own homeserver, the account `@<T>` in the room; on a central homeserver, `@<T>.<M>`, invited by `@porelo.<M>`. Shown as the nickname `N` (1 to 64 characters, no control characters, `@` or `:`) or else as `T`; enrolled again with another, renamed, its id kept. A task account in another project's room is refused (78) and left as it is | `secrets` (the task's environment), `address` (its user id) |
| `retire --project P --task T [--machine M]` | On the account's own homeserver, deactivates `@<T>`; on a central homeserver, `@<T>.<M>` leaves the room and logs out every session, so the name can be used again. One in another project's room is refused (78) and left active | `{}` |
| `join --project P --person N [--reset] [--token] [--display N] [--machine M] [--loopback-only]` | On the account's own homeserver: a person's account in the room, with a new password. An existing account is refused (77) without `--reset`, except one `part` took out of this project's room that is joined or invited in no project's room: it is taken back without `--reset`. An account that left by itself, such as a retired task's, never is. `--display` sets the display name with the same validation as `enroll`. `--token` logs out every earlier session, including on reset and when taking back a parted account, and keeps the password private. On a central homeserver: refused (77); people are managed at the homeserver itself | Without `--token`: `login` and `shown`; the password is printed once, and the admin-room command that set it and its reply are redacted at once. With `--token`: exactly `{"login":{"user":"@N:server","room":"!room-id"},"secrets":{"PORELO_MATRIX_ACCESS_TOKEN":"<token>"}}`; no password or `shown`, and no other secret |
| `part --project P --person N [--loopback-only]` | On the account's own homeserver: the administrator kicks a joined or invited person out of the project's room. In no other project's room, the account gets a fresh password nobody is shown and logs out every session through the client API; it is never deactivated, so `join` without `--reset` can take it back. In another project's room too, its password and sessions stay valid. No account or no membership: nothing to do. On a central homeserver: refused (77) | `{"removed":"@N:server"}` when locked; `{"kept":"@N:server","why":"a member of #porelo-Q:server too"}` when another project keeps it; `{}` when nothing was done; exit 0 in all three |
| `clear --project P [--machine M]` | On the account's own homeserver: deactivates every account in the project's room (its tasks', its relay's, its people's) that is in no other project's room, deletes the room with its alias, and forgets the relay's poll position. On a central homeserver: this machine's relay `@porelo-relay-<P>.<M>` and its tasks `@<T>.<M>` in the room leave the room and log out every session, so their names can be used again, and the relay's poll position is forgotten; `@porelo.<M>`, the room, people and other machines' accounts stay. An account in another project's room too stays active there and is named in `kept` | `removed` (what went) and `kept` (each `what` and `why`) |
| `clear [--machine M]` | The account's own homeserver once no project uses it: stops and disables it, removes its container and database, its port and registration token, and every poll position. The machine's name is taken and changes nothing. Reads no settings | the same |
| `settings` | Checks the settings as a draft, with no network and no environment | `{"refused": […], "warnings": […]}` |

- **Settings:** `homeserver` (a central homeserver; requires `room`), `room` (an alias `#local:server` or a room id,
  required with `homeserver` and refused without it), `ca_file` (absolute), `tls_verify` (`on`/`off`),
  `registration_token_file` (absolute, only with `homeserver`). Anything else is refused (78).
- **The printed `secrets` from `setup` and `enroll` name `PORELO_MATRIX_CA_FILE` and `PORELO_MATRIX_TLS_VERIFY`**, so an
  inherited value never outweighs what the project configured.
- **Run again, each keeps what exists.** `part` twice is harmless: the second prints `{}`. `retire` twice
  is harmless, and so is `clear`: it says what it removed and what it could not, with why, exits 0,
  and the second time finds nothing. Porelo removes its own entries for the transport beside it.
  A project cleared can be set up again.
- **`--loopback-only`**, for an offline project: a `homeserver` that is not loopback is refused (78)
  before any request.
- **A central homeserver** (`homeserver` set) needs its registration token: read from
  `registration_token_file`, which another account must not be able to read (78 otherwise), else from
  `PORELO_MATRIX_REGISTRATION_TOKEN`. It also requires `--machine M` (or `PORELO_MATRIX_MACHINE` kept from
  `setup`; a `--machine` that differs from `PORELO_MATRIX_MACHINE` is refused with 78).
- **The project's room is checked:** on the account's own homeserver it is taken only if it is Porelo's —
  made by its administrator, invite-only, guests forbidden — and a room under the alias that is anybody
  else's, or was opened since, is refused (78) by `setup`, `enroll`, `join` and `part`, while `clear`
  leaves it and its members alone. On a central homeserver the room named by `room` must be invite-only
  with guests forbidden; otherwise `setup` leaves it and refuses (78), and `enroll` refuses (78).
- **Never a second administrator or a taken machine name:** on the account's own homeserver, an `@porelo`
  Porelo holds no token for is refused (78), except by the first `setup` after a `restore`, which takes it
  back. If its token is refused and no `@porelo` exists, the homeserver was reset (78, saying so). On a
  central homeserver, a `--machine` name another machine holds is refused (78).
- Names — a project's, a task's, a machine's or a person's — are lower-case letters, digits and `. _ = -`;
  a person's may not begin with `porelo`. A machine's is a host name, so `--machine` is taken without regard to
  case: its ASCII letters are folded to lower case.

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
| `describe` | Prints what the transport is, reading nothing: scheme `matrix`, `waitsOn: "the room's homeserver"` (one line, at most 80 characters), `poll`, `confirms: read`, `max_bytes: 10240`, `shown: true`, `persons: true`, `direct: true`, `waits: true`, `mention: true`, `display: true`, `takes: ["machine"]`, its credential and its lifecycle verbs |
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
