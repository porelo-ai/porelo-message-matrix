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
| `XDG_STATE_HOME`, else `HOME` | Where `poll` keeps its position: `…/porelo/transports/matrix/` |

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
| `setup --project P [--loopback-only]` | Starts the local homeserver if no `homeserver` is set; registers `@porelo`, the administrator; makes the room `#porelo-<P>` (invite-only, unlisted) and the relay `@porelo-relay-<P>` | `account` (the administrator's token), `secrets` (the relay's environment, for `poll`), `conversation` (the room id), `reaches` (a JSON array of `"host:port"`, e.g. `["127.0.0.1:8008"]`) |
| `enroll --project P --task T [--display N] [--loopback-only]` | The account `@<T>` in the room, shown as the nickname `N` (1 to 64 characters, no control characters, `@` or `:`) or else as `T`; enrolled again with another, renamed, its id kept | `secrets` (the task's environment), `address` (its user id) |
| `retire --project P --task T` | Deactivates `@<T>` | `{}` |
| `join --project P --person N [--reset] [--loopback-only]` | A person's account in the room, with a new password; a second join is refused (77) without `--reset` | `login` and `shown`; the password is printed once, and the admin-room command that set it and its reply are redacted at once |
| `clear --project P` | Deactivates every account in the project's room (its tasks', its relay's, its people's) that is in no other project's room, deletes the room with its alias, and forgets the relay's poll position. An account in another project's room too stays active there and is named in `kept` | `removed` (what went) and `kept` (each `what` and `why`) |
| `clear` | The account's own homeserver once no project uses it: stops and disables it, removes its container and database, its port and registration token, and every poll position. Reads no settings | the same |
| `settings` | Checks the settings as a draft, with no network and no environment | `{"refused": […], "warnings": […]}` |

- **Settings:** `homeserver`, `ca_file` (absolute), `tls_verify` (`on`/`off`), `registration_token_file`
  (absolute, only with `homeserver`). Anything else is refused (78).
- **The printed `secrets` always name `PORELO_MATRIX_CA_FILE` and `PORELO_MATRIX_TLS_VERIFY`**, so an
  inherited value never outweighs what the project configured.
- **Run again, each keeps what exists.** `retire` twice is harmless, and so is `clear`: it says what it
  removed and what it could not, with why, exits 0, and the second time finds nothing. Porelo removes its
  own entries for the transport beside it. A project cleared can be set up again.
- **`--loopback-only`**, for an offline project: a `homeserver` that is not loopback is refused (78)
  before any request.
- **A homeserver that is not this machine's own** needs its registration token: read from
  `registration_token_file`, which another account must not be able to read (78 otherwise), else from
  `PORELO_MATRIX_REGISTRATION_TOKEN`.
- **The project's room is taken only if it is Porelo's:** made by its administrator, invite-only, guests
  forbidden. A room under the alias that is anybody else's, or was opened since, is refused (78) by
  `setup`, `enroll` and `join`, and `clear` leaves it and its members alone.
- **Never a second administrator:** an `@porelo` Porelo holds no token for is refused (78), except by the first
  `setup` after a `restore`, which takes it back. If its token is refused and no `@porelo` exists, the homeserver
  was reset (78, saying so).
- Names are lower-case letters, digits and `. _ = -`; a person's may not begin with `porelo`.

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
| `describe` | Prints what the transport is, reading nothing: scheme `matrix`, `poll`, `confirms: read`, `max_bytes: 10240`, `shown: true`, `persons: true`, `direct: true`, `waits: true`, `mention: true`, `display: true`, its credential and its lifecycle verbs |
| `check` | Whether it could work now: the configuration complete, the homeserver answering, the token accepted. 0 usable, 2 not |
| `send <file> <sig> --to <room id \| @user:server> [--shown <line>] [--mention @user:server]...` | To a person's user id: into the acting account's direct chat with them, opened (invite-only, marked direct) if there is none. One `m.text` event: the message in `org.fuin.porelo.message`, the signature in `org.fuin.porelo.signature`, and as its `body`, what a client shows, the line `--shown` names. In a room, each `--mention` is named in `m.mentions` and shown as a pill, by the name that person's or task's account shows, before the line, so that person's client highlights it; without one, `m.mentions` is empty and the message notifies nobody. In a direct chat nobody is mentioned. Prints `{"reference":"<event id>"}`. A retry is the same event |
| `rename --display <nickname>` | Run with a task's token: the name its account shows in the room and its direct chats becomes the nickname. Its id, its token and every address stay. Prints nothing |
| `poll --into <dir> [--persons \| --direct] [--wait <s>]` | Writes every new Porelo message as `<time>--matrix-<uuid>.json` with its `.sig` and its `.sender` (the account that sent it, as the homeserver says), each file whole, from `org.fuin.porelo.message`. With `--persons`, a person's text in the room too, as `<time>--matrix-person-<uuid>.json`, no `.sig`. With `--direct`, run with a task's token: only its direct chats with people, as person files marked `direct`. With `--wait` (1 to 30 s), the homeserver holds the sync open and it answers as soon as a message to hand over arrives; an answer with nothing in it, such as a read receipt the homeserver woke for, does not end the wait. Anything else is skipped and named on stderr |
| `receipt <reference> --by <account>` | `{"state":"read","at":…}`, `{"state":"delivered"}` or `{"state":"unknown"}` |
| `read <reference>` | Posts the account's read receipt, so the sender's `receipt` answers `read`. The reference is the event id or the file name `poll` gave, less `.json` |

- **A message over `max_bytes` is refused** (65), whatever it holds; so is one that is not UTF-8.
- **The line is Porelo's**, made on the host from the checked message: sender, addressee and text. The
  transport carries it unread, and cuts it where the event would not fit, ending "… (cut; N characters in
  all)"; the message is never cut. Without `--shown` the body is "A Porelo message.", never the message.
- **An event without `org.fuin.porelo.message`**, the message in its body as before, is not delivered.
- **A person's file** is `{"sender", "to": [...], "body", "eventId", "replyTo"?, "direct"?, "at"}`.
  - **Its `to`** is whom it is meant for, as Matrix states it: the accounts the client marked as mentioned
    (`m.mentions`, else `matrix.to` links), plus the sender of the message it replies to, less the writer
    and the poller. It may be `[]`. Every task reads the room, so who reads it is Porelo's, not `to`'s.
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
| 75 | Temporary: the homeserver unreachable or busy, another poll running, or a backup copying the positions |
| 76 | An answer the transport does not understand, or a redirect |
| 77 | Refused by the homeserver: the token, or a room the account is not in |
| 78 | The configuration: missing, unknown, an untrusted certificate, a reset homeserver, a loopback port another uid listens on; or a machine that will not keep the tokens from the account's other processes |

The reason goes to stderr, and never contains a token.
