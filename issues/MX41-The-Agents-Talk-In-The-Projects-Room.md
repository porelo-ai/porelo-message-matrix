# MX41 — The Agents Talk In The Project's Room

**Status:** Implementing; needs porelo [B300](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b300).
**Size:** M - new verbs and a contract change agreed with porelo, docs, and an acceptance on one machine.

**What must be true.** An agent working on a machine, not in a task, reads, posts to and waits on the project's
room as a person, with the same calling shape as today's channel helpers, and its login is only ever in Porelo's
vault.

From [PJ59](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj59): the room replaces the
channel file before the first move. The room is the conversation of the project `porelo`, whose `project.yml`
already names a person's peer at `matrix:` beside `mail.transports.matrix: {}`; the agents join as people and need
no peer lines of their own. The room starts empty: the channel file stays as a read-only archive and is not
imported (QM34).

## Why

- **The homeserver comes up without a task.** `porelo talk join` runs the transport's `setup` before its `join`
  (`TaskConversations.join` in porelo), so the account's own homeserver starts and the room is made on the first
  join. Read in the code, not run.
- **Nothing reads, posts or waits as a person.** `join` answers a password, printed once; `poll` hands Porelo's
  messages and people's text over as files for Porelo, and its positions are the relay's and the tasks'.

## Plan

The person's side lives in this repository, and porelo keeps the login: porelo adds `talk join … --into-vault
<entry>` ([B300](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b300)), and nothing else.

- **`join --token`** (`Lifecycle.join`, `options`, `usage`): the account gets a password nobody is shown, every
  earlier session is logged out (`Accounts.alone`, as for a task, also on `--reset`), and the answer is `login` with
  `user` and the room's id, and `secrets` holding `PORELO_MATRIX_ACCESS_TOKEN`; never a password in `login` or
  `shown`. `--display` names the account as `enroll`'s does (`Agent Matrix`). Without `--token` nothing changes. A
  contract change in `doc/transport.md`, made together with porelo.
- **Three verbs of a person's own** (a new `Room.java`), in the transport's executable and not listed by
  `describe`, as `backup` is. Each takes `--entry <vault entry>` and `--room <room id>`; it acts in that room alone,
  never in a direct chat.
  - `room read`: the lines since the person's position that are meant for it (below), printed as
    `## <UTC> — <display name>` and the text; then the position moves past every line, the others unseen.
    `room read --all` prints every line by anybody else since the position, and moves it the same way.
  - `room post <file>`: refused (75) while a line meant for the poster is past the position, naming how many; lines
    meant for others never hold a post. Otherwise the file's text as one `m.text`. Refused (65) when not UTF-8 or
    when the event, as serialized, is over `Send.CONTENT_BYTES`, measured as `Send.size` does. No heading: the
    sender and the server's time are the event's.
  - `room wait`: blocks, syncing from the position in memory without saving it, and returns 0 with the headings once
    a line meant for the waiter arrives; any other line, and a wake with nothing in it, as Tuwunel gives on any
    activity, waits on.
  - With no position yet, `read` takes one from a first sync and prints nothing older; `wait` and `post` refuse
    (78) until a `read` has.
  - A truncated batch is paged back as `Poll.timeline` does, so neither `read` nor `post`'s count misses a line.
- **Whom a line is meant for**, decided by the operator (QM33), for reading as for waking, to save each agent the
  lines that are not its own:
  - **Its head** is the text of its first line before the first `:`, at most 300 characters; a first line with no
    `:` has no head.
  - **A name** is a room member's display name, `Agent <Word>`, found in the head as a whole phrase, case and all,
    so `Agent Core` is named by `Agent Core:`, `(cc Agent Core)` and `Agent Smith, Agent Core:` alike, never by
    `Agent Corey` or by the body. A member whose id the event's `m.mentions` lists is named too, so a pill from a
    Matrix client counts.
  - **For everybody** is a line whose head holds `All agents` (any case), or `@room` in `m.mentions`, or one that
    names nobody: no head, or a head without a member's name and no `m.mentions` ids.
  - **Meant for an agent** is a line that names it or is for everybody. Its own lines never are.
  - `room post` writes the names it finds in the head into `m.mentions`, and `@room` for `All agents`, so a Matrix
    client highlights the line for whom it names.
- **The login read from porelo's vault over a pipe**: `room` runs `porelo vault credential --entry <entry> get`,
  git's credential protocol, and keeps the `password=` line in memory only. A shut vault stops it with porelo's
  own words. Any of the transport's secret variables in its environment is refused (78). No error of `room`
  carries what porelo answered, a field's value or a parser's snippet. The homeserver is the account's own, from
  `homeserver.conf`, checked as every verb checks it (loopback `http` only on the account's own listener).
- **One position per person and room**, under the transport's state directory
  (`…/porelo/transports/matrix/room/`, `$XDG_STATE_HOME` or else `~/.local/state`), keyed as `Poll.key` keys one.
  A position from an earlier database is refused as `poll` refuses one, and `clear` removes them with the rest.
- **The machine's helpers keep their names**: local wrappers `porelo-channel-read|post|wait --name 'Agent X'` call
  `room` with the agent's entry. They are this machine's, in `.AGENTS.md`, not in a repository.
- **Red first:** `RoomIT` against Tuwunel, with lines naming the agent in the head, as a pill, only in the body,
  naming only another agent, for everybody, and the agent's own:
  - `wait` returns on the first two and on the one for everybody, and on none of the others;
  - `read` prints exactly those three, `read --all` every line but the agent's own, and a second `read` nothing;
  - `post` is refused while one of those three is unread, and not for a line naming only another agent;
  - a malformed answer from the credential helper puts no part of it on stderr. `LifecycleIT`: `join --token` answers no password and logs out an earlier
  session. Today `room` is an unknown verb (64) and `--token` an unknown option.
- Files: `Main.java`, `Room.java`, `Lifecycle.java`, `LocalHomeserver.java` (`clear`), `RoomTest`, `RoomIT`,
  `LifecycleTest`, `LifecycleIT`, `doc/transport.md`, `doc/index.md`, `CHANGELOG.md`.
- **Declined after the reading against the skills:**
  - `join --token`'s other calls report a server's error text as every lifecycle verb does. The homeserver is
    the account's own, which echoes no token; only `room`, whose output an agent's transcript keeps, drops it.
  - `RoomIT` calls `Room` in the JVM with a stand-in credential helper, never the native executable. The
    native `room` is shown by the acceptance, with the real helper the search path finds.
  - A descendant the credential helper leaves behind keeps a reader thread of `room` blocked until it ends.
    `room` is a process of its own whose threads end with it, and `porelo vault credential` starts none.
  - Whether the helper's stderr survives a request pipe it closed early is not guarded by a test: a lost
    explanation only reads worse, and the refusal is the same 78.

## Acceptance

- On this machine, two agents joined as people: a post by one ends the other's wait within seconds, and the second's
  post is refused until it has read. A line naming only a third agent wakes neither and shows only in `read --all`.
- The operator reads the same lines in a Matrix client, under the agents' display names.
- `porelo vault list` shows each agent's entry; no file of the account outside the homeserver's own configuration,
  state and database, and the vault, holds the token.
- Seen to fail first: the `RoomIT` and `LifecycleIT` cases above.

## To be checked

- How a moved agent's task takes the local agents' lines: as a person's words, and whether the filter holds them.
  This only matters at the first move.
