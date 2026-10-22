# Decisions

Settled reasoning, accepted risks included: what was decided, why, and what would change the answer.

| Subject | What holds |
|---|---|
| [The homeserver is Tuwunel](#the-homeserver-is-tuwunel) | Built and tested against Tuwunel, pinned by digest; Conduit is the measured alternative. |
| [A message is a text event: a line for a person, the message and its signature beside it](#a-message-is-a-text-event-a-line-for-a-person-the-message-and-its-signature-beside-it) | One `m.text` event: the line for a person as its body, the message and its signature in fields of their own. |
| [A person's words reach the tasks; whom they are meant for is what Matrix states](#a-persons-words-reach-the-tasks-whom-they-are-meant-for-is-what-matrix-states) | A person's text in the room and in a direct chat reaches the tasks; mentions and replies say whom it is meant for. |
| [The position of a poll is saved after its files](#the-position-of-a-poll-is-saved-after-its-files) | A crash delivers a message twice, never skips one. |
| [A position from an earlier database is refused, by the rooms kept beside it](#a-position-from-an-earlier-database-is-refused-by-the-rooms-kept-beside-it) | A poll whose account is in none of the saved rooms stops with 78. |
| [`max_bytes` is what every message fits, not what most do](#max_bytes-is-what-every-message-fits-not-what-most-do) | 10240 bytes, the worst case of escaping in a 64 KiB event. |
| [A receipt is looked up by the event's own room, and read means read up to](#a-receipt-is-looked-up-by-the-events-own-room-and-read-means-read-up-to) | The room comes from the event; a receipt on a later event means read. |
| [Accepted risk: the homeserver holds the conversation unencrypted](#accepted-risk-the-homeserver-holds-the-conversation-unencrypted) | No end-to-end encryption on a homeserver the operator runs; the filter and Porelo's signatures carry the guarantees. |
| [The packages are built beside the executable, in one module](#the-packages-are-built-beside-the-executable-in-one-module) | All four packages come from the native profile of the executable's module. |
| [The local homeserver is chosen, never pulled in](#the-local-homeserver-is-chosen-never-pulled-in) | `Provides: porelo-homeserver`; the transport neither depends on it nor recommends it. |
| [The transport brings the filter](#the-transport-brings-the-filter) | The transport's package depends on `porelo-message-sluice-filter`. |
| [A homeserver's certificate: public, the operator's own, or - for development - unchecked](#a-homeservers-certificate-public-the-operators-own-or-for-development-unchecked) | A public certificate, a CA file, or the check switched off loudly for development. |
| [Tokens and passwords never travel or stay readable](#tokens-and-passwords-never-travel-or-stay-readable) | Plain `http` only on loopback and only to the account's own listener, the running transport not dumpable, a password reset redacted at once, the project's room taken only if it is Porelo's. |
| [After a restore, setup takes the administrator back](#after-a-restore-setup-takes-the-administrator-back) | Only behind `restore`'s mark, only on the account's own homeserver; the new password reaches the homeserver's run through podman's environment. |
| [No administrator on a central homeserver](#no-administrator-on-a-central-homeserver) | Each machine acts only as the accounts it made, with passwords derived by HMAC-SHA256 from the machine's secret in `account`. |

## The homeserver is Tuwunel

**Decided:** the minimum arrangement - one machine, the homeserver in rootless podman, loopback only,
no federation - is built and tested against Tuwunel, pinned by digest in `homeserver/Dockerfile`. What
it is configured with and what it was measured to do are in [the homeserver](homeserver.md).

**What was weighed.** Three single-binary homeservers with an embedded database, each measured the same
way against the calls the transport depends on:

| | Tuwunel 1.9.3 | Conduit 0.10.14 | Continuwuity 26.9.1 |
|---|---|---|---|
| Image | 107 MB | 66 MB | 105 MB |
| First start | 0.6 - 2.8 s | 3.4 - 6.1 s | 0.6 s |
| Memory at rest | 33 - 56 MB | 8.5 MB | 34 MB |
| Newest client API | v1.19 | v1.12 | v1.18 |
| Configured registration token works from the first start | yes | yes | no |
| Every call the transport depends on | passed | passed | not reached |

Tuwunel was measured, against the pinned image, to do what the transport relies on: loopback only and
federation refused; registration only with the token; a message and a field beside it returned byte for
byte; a repeated transaction answered with the same event; read receipts; an account deactivated, its
messages kept. On a 2-vCPU VM it starts in under three seconds and rests at 33 to 56 MB.

**Continuwuity is out** because it cannot be started and forgotten: it ignores the configured
registration token until a first account exists, and that account needs a one-time token it prints
only in its log, or its interactive console.

**Tuwunel over Conduit.** Both pass every call, and Conduit costs a quarter of the memory. Tuwunel
implements the current client API and states that it is maintained by full-time staff, which counts
for more in a server that holds the whole conversation than tens of megabytes do.

**What would change the answer:** Tuwunel's maintenance stopping, or memory becoming the constraint on
the machines Porelo runs on. Conduit is then the measured alternative, configured the same way with the
prefix `CONDUIT_`, `CONDUIT_CONFIG` set empty and the container port `6167`.

## A message is a text event: a line for a person, the message and its signature beside it

**Decided:** `send` puts the message into the room as one `m.text` event: the message as a JSON string in
`org.fuin.porelo.message`, its detached signature, base64, in `org.fuin.porelo.signature`, and as the
`body` the line Porelo made for a person, `--shown`.
Porelo writes the line from the checked message's text alone, and Matrix says whom it is for: in the room,
each account it names is a mention (`send --mention`: a pill before the line and `m.mentions`), and one
for the whole room mentions nobody; to a person alone, it goes into their direct chat (`send --to @user`).
The transport carries the line unread; only the line is ever cut, saying its full length; `poll` reads
only this form.

**Why:** the room is the project's conversation, and a person reads it with any Matrix client, which
shows the `body` and ignores a field it does not know. Shown the message itself, a person sees JSON,
correct but not readable. The line is made after the filter, so only of
what it checked, and never by a task. The cut is the transport's because only it knows the event's size.
The signature is bytes, and base64 keeps it byte for byte. Measured against Tuwunel: a message with
non-ASCII text, a tab, a CRLF and a trailing newline comes back from a sync identical, and so does the
field beside it; the largest message escaped at its worst, with a 2 KiB signature and a cut line, is
taken.

**What follows:** a JSON string carries text only, so a message whose bytes are not valid UTF-8 is
refused rather than carried with its bytes silently replaced. Porelo's messages are JSON, so this refuses
nothing Porelo sends. A room with events of the earlier form is reset rather than read.

**What would change the answer:** Porelo sending messages that are not text, or the size of one event
(64 KiB for the whole event) becoming too small for a message and its line.

## A person's words reach the tasks; whom they are meant for is what Matrix states

**Decided:** a person talks with the tasks through messaging alone. A person writes in the project's
room, or in a direct chat with one task's account, and the tasks get it.
- `poll --persons` (the relay) hands over a person's text in the room. `poll --direct` (each task's
  account) hands over what is said in its direct chats. Each is a file of its own kind beside the Porelo
  messages.
- **Every task reads the whole room.** `to` only
  says whom a message is meant for: the accounts the client marked as mentioned, and the sender of what
  it replies to.
- A person's words are not filtered on the way in. Porelo takes them only from a member of the room.

**Why so:** the transport still reads no meaning into a message. A mention and a reply are structure that
the person's client puts into the event; the text itself is handed over as typed. Porelo's own messages
are still told by the transport's marks, the message field and the signature beside it. A person's file
has no `.sig`, and its name says whose it is. Whether and how it reaches a task is Porelo's.
A direct chat is taken only from somebody who already shares the project's room with the task's account,
so no stranger opens a way in, and only unencrypted: a task holds no keys, so an encrypted chat is declined
with a reason the person's client shows, and an encrypted message is named on stderr, never lost
silently. `describe` announces both, so a Porelo that knows neither never gets such a
file.

**What would change the answer:** a person's words needing a check on the way in after all; the transport
hands them over the same way.

## The position of a poll is saved after its files

**Decided:** `poll` keeps the homeserver's sync position under `$XDG_STATE_HOME/porelo/transports/matrix/`,
one file per homeserver and account, and saves it only once every file of that poll is written.

**Why:** Porelo passes a transport no directory for its state, and drops a message whose id it has
already delivered. So delivering a message twice after a crash costs nothing, while skipping one loses
it: the position is saved late, never early. A lock beside it keeps two polls of one account from
writing the same position.

**What would change the answer:** Porelo giving transports a state directory of their own.

## A position from an earlier database is refused, by the rooms kept beside it

**Decided:** the position file holds, after the position, the rooms the account was in when it was
saved. A poll whose account is in none of them any more stops with 78 and names the file; a file from
before, with the position alone, is taken and gains the rooms.

**Why:** measured on Tuwunel, a position from a database since removed is answered with 200, no events
and the new database's own position - so everything sent there before that poll would be lost without a
word. A position is opaque by the Matrix specification, so comparing it with the server's is not ours
to do; room ids are random, and a reset makes new ones. Nothing is repaired by the transport: a room the
account was taken out of looks the same from here, and a person should decide.

**What would change the answer:** a homeserver refusing a position it did not give out.

## `max_bytes` is what every message fits, not what most do

**Decided:** `describe` declares `max_bytes: 10240`, and `send` refuses a larger message with 65.

**Why:** Matrix limits an event to 64 KiB in all, and a message travels inside it as a JSON string, where
escaping grows it by up to six times. Measured against Tuwunel with a 256-byte signature: plain ASCII
fits 64,397 bytes, three-byte UTF-8 64,401, quotes 32,197, control characters 10,727. A limit is only
something Porelo can rely on if it holds whatever the message contains, so it is the worst case, with room
for the envelope and a signature of up to 2 KiB. `send` keeps the same number rather than trying a larger
message, so what `describe` says and what `send` does never disagree.

**What would change the answer:** Porelo's messages being known never to contain characters that escape
to more than two bytes - Porelo's messages are JSON, whose strings already escape control characters -
which would allow 30 KiB; or messages carried as a file upload instead of an event body.

## A receipt is looked up by the event's own room, and read means read up to

**Decided:** `receipt` finds the event among the rooms the account is in, takes the room from the
event's own `room_id`, and answers `read` when `--by`'s read receipt is on that event or a later one.

**Why:** a Matrix read receipt marks the latest event an account has read, not each one, so a receipt
on a later event is the only sign that an earlier one was read. The room is taken from the event and not
from the path it was asked under because Tuwunel, measured, answers an event asked for under any room the
account is in - so trusting the path picked the admin room, where the addressee is not, whenever that
room happened to be listed first. Porelo names `--by`, because which account a message was for is inside
the message, which the transport does not read.

**What would change the answer:** Porelo handing the room to `receipt` as well; the lookup through the
account's rooms is then no longer needed.

## Accepted risk: the homeserver holds the conversation unencrypted

**Decided:** no end-to-end encryption, on a homeserver the operator runs.

**The exposure.** Every message that passed the filter is stored by the homeserver, readable by whoever
runs it: through its database, and through the server's administrator account, which is the first
account registered on it. Nothing is removed with a task - a deactivated account's messages stay in the
room, measured against Tuwunel - so the room keeps the whole conversation for as long as the homeserver
does. And the confidentiality boundary is the room, not the peer: every participant of the project
receives every message, including the ones addressed to another task, and delivery is filtered only
afterwards, by the receiving host.

**Why it stays.**
- **On one machine the exposure adds nothing.** The homeserver runs in podman beside Porelo, published on
  loopback only, with federation off - measured: the machine's own address gets no answer, and the
  federation API refuses. The messages stay on the host, where Porelo's own mailboxes already hold them.
- **Authenticity never rested on Matrix.** Porelo signs every message and verifies it against the key
  listed for the peer; what arrives unverified is held, whatever the room says. So a homeserver can read
  messages but cannot forge one - room membership is not authorization.
- **Encryption would buy confidentiality against the operator, who runs the server.** For an unattended
  sender it means device keys, cross-signing and key backup for every task ever started, and a client
  that cannot decrypt shows an empty room rather than an error - a failure that reads as "the chat is
  broken" and is hard to trace.
- **What passes is prose.** The filter lets through only what it judges a message may carry; it is what
  stands between a task's secrets and the room.

**What would change the answer:** a homeserver somewhere the operator does not control - hosted by
somebody else, or federating with servers the operator does not run. Then the filter's guarantee is the
only one left, and the decision is taken again rather than inherited. Two projects whose tasks must not
read each other's messages are two rooms, and therefore two projects, never two peers in one.

## The packages are built beside the executable, in one module

**Decided:** all four packages - the transport's and the local homeserver's, each as `.deb` and `.rpm` -
are built by the native profile of the one module that builds the executable, not by modules of their
own.

**Why:** the repository makes one executable, and the transport's packages carry exactly it, its bill and
its license. The homeserver's packages carry one file, its unit, which the same build writes from the
Dockerfile's pin. Modules of their own would be a second place for what the native profile already
knows - where the executable and the unit are, what they are called, which version they have - and the
second copy is the one that goes stale.

**What would change the answer:** a second program to package, or a package that grows past a file or
two; a module per package then pays for itself.

## The local homeserver is chosen, never pulled in

**Decided:** `porelo-matrix-homeserver` declares `Provides: porelo-homeserver`, and the transport's package
neither depends on it nor recommends it. A machine's setup offers it as a kind of its own, beside the
transports, chosen with Matrix unless the project names a homeserver of its own.

**Why:** a machine on a central homeserver must not run a local one, and a recommendation is installed by
default; offered on its own, it is one deliberate choice in the same place as the transport, and a
machine that chose Matrix alone is told by `setup`'s 78 what is missing.

**What would change the answer:** the local homeserver becoming the only arrangement Porelo supports.

## The transport brings the filter

**Decided:** the transport's package depends on
`porelo-message-sluice-filter` (`Depends:`, and `Requires:` in the `.rpm`), where Porelo only recommends it.

**Why:** Porelo sends nothing without the filter, so a transport installed without it, by
`--no-install-recommends` or by a wizard that installs only what was ticked, can carry nothing, and a
person meets "no message filter is installed". Depending on it, every way of installing a transport
brings the filter.

**What would change the answer:** a transport that carries messages Porelo does not filter, which none does.

## A homeserver's certificate: public, the operator's own, or - for development - unchecked

**Decided:** three cases. A public certificate needs nothing. An intranet's CA or a
self-signed certificate is trusted through `PORELO_MATRIX_CA_FILE`. `PORELO_MATRIX_TLS_VERIFY=off` switches
the check off, for development only, with a warning at every start.

**Why the CA file and not only the switch:** the native executable trusts the authorities it was built
with, not the machine's, so an intranet's server is refused unless the transport is told about its CA.
Handing it the certificate keeps the check - the host name included - while the switch drops it, and
with it the only thing that keeps the access token from whoever answers in the homeserver's place.

**Why the switch exists anyway, and why so loudly:** a developer's throwaway setup should not need a
certificate to be exported first. So it is there, and it says so on every start, and it cannot be
combined with a CA file - a configuration that asks for both is refused rather than guessed at.

**What would change the answer:** the switch being found in any configuration meant for use; it then
goes, and the CA file is the only way to trust an unusual certificate.

## Tokens and passwords never travel or stay readable

**Decided**, measured against Tuwunel:
- **A homeserver over plain `http` is taken only on this machine's loopback.** Elsewhere every request
  would carry an account's token readable to anyone on the way, so it is refused (78), in the settings and
  in the environment.
- **A token goes to a plain-`http` loopback port only when the account itself listens there.** Before every
  request, the kernel's tables of listening sockets (`/proc/net/tcp`, `/proc/net/tcp6`) name the
  owner of the port, on its own address or a wildcard. Another uid is refused (78) before any request: while
  the account's own homeserver is down, another account of the machine can take its port. Measured: the
  local homeserver's published port belongs to the account itself (rootless podman's `pasta`), and a
  listener in a user namespace of its own shows a sub-uid, as another account's would.
- **A password reset is redacted from the admin room at once.** Tuwunel sets a password only through an
  admin-room command, and the command and the server's reply both hold it. Both are redacted right after,
  so no event the homeserver serves still holds the password.
- **The running transport is not dumpable.** Porelo hands it the tokens as its environment, and a fresh
  executable is dumpable whatever started it, so any process of the account could read them from
  `/proc/<pid>/environ` while it runs - `poll --wait` up to 30 s. Its first step is `prctl(PR_SET_DUMPABLE, 0)`,
  read back, so its `/proc` files and memory belong to root; a machine that refuses it gets 78.
- **The project's room is taken only if it is Porelo's.** It must have been made by Porelo's administrator,
  be invite-only, and have guests forbidden. A room somebody else put under `#porelo-<project>` first, or
  one opened since, is refused: tasks and people would otherwise be put into a room of somebody else's
  making.

**Risk accepted:** redaction strips an event as the homeserver serves it. Whether the original bytes
leave the database at once, or only when RocksDB next compacts, is the homeserver's matter. The local
homeserver's database is a podman volume of the account itself, readable by no other account.

**Risk accepted:** between `exec` and the `prctl` call the environment is still readable - 15 to 40 ms
under `strace`; a shell loop reading `/proc/<pid>/environ` right after each start caught the token in 25 of
300 starts. A process of the account that watches `/proc` for the transport's starts can still take a token.
Only a token that is not in the environment at all closes it, which changes Porelo's contract.

**Risk accepted:** between the check of the port's owner and the connection there is a window of
milliseconds. Another account cannot bind a port this account holds, so only a homeserver that dies in that
window lets another account take the port before the request. A socket the account alone can open would
close it, which changes porelo's and porelo-frontend's side too.

**What would change the answer:** Tuwunel offering a way to set a password that is not a room event; it
would then replace the command. Porelo handing the tokens over on a pipe instead of the environment; the
transport then reads them there and the window is gone.

## After a restore, setup takes the administrator back

**Decided:** the first `setup` after a `restore` takes `@porelo` back instead of refusing it: the unit
stopped, one run of the homeserver's own program that sets a new password, the unit started, a login, and
every other session of the account logged out. Only where `restore` left its mark, and only on the account's
own homeserver; anywhere else an `@porelo` Porelo holds no token for is refused, as before. The relay and each
task enrolled again are taken back by the admin room as before, their other sessions logged out too.

**Why:** Porelo's vault is not copied with the conversation, so on a new machine nothing holds `@porelo`'s
token, while the restored database has `@porelo`. Refusing loses the room at every new machine. Logging the
other sessions out makes every token the copy holds worthless once its account is taken back.

**Risk accepted:** the new password reaches the run in its environment - `TUWUNEL_ADMIN_EXECUTE`, which podman
takes from its own environment, so it is on no command line - and the run repeats it on its standard output,
which is discarded, with `--log-driver=none` keeping it out of the journal. For the run's second or so it is
readable in the environment of podman and of the container, by the account alone. Whoever can run `restore`
and `setup` becomes the restored homeserver's administrator, which that account is already, owning the
database.

**What would change the answer:** Tuwunel setting a password from a file or standard input, which would then
replace the environment; Porelo's vault coming with the copy, which would make the taking back unneeded.

## No administrator on a central homeserver

**Decided:** on a central homeserver (`homeserver` and `room` set), no machine registers or uses the
homeserver's administrator account. Each machine registers `@porelo.<machine>`, its relay
`@porelo-relay-<project>.<machine>` and its tasks `@<task>.<machine>` with its registration token, and acts
only as the accounts it made, through the client API. Every account's password on that machine is derived as
the HMAC-SHA256 of the machine's secret over the account's localpart; `setup` makes the secret once and prints
it in `account` as `PORELO_MATRIX_MACHINE_SECRET`, kept in Porelo's vault as one key of `account` and handed to
every lifecycle verb on that machine.

**Why:**
- **No machine may act on another machine's accounts.** On the account's own homeserver, `@porelo` is the
  server's administrator and resets passwords or deactivates accounts through the admin room. On a central
  homeserver shared by several machines, an administrator token on each machine would let any one machine take
  over or deactivate another machine's tasks and people's accounts.
- **Porelo hands a task's own secrets only to its `retire`.** `enroll` run again for a task receives no
  earlier task map, and `clear --project` receives only `setup`'s maps, never a task's. A random password
  printed in a task's `secrets` would be lost to both, so a task whose token was lost could not be taken back
  by `enroll` and a task Porelo forgot without a `retire` could not be removed by `clear --project`.
  Deriving each password from `PORELO_MATRIX_MACHINE_SECRET` lets `setup`, `enroll`, `retire` and
  `clear --project` log in as any account of that machine, leave the project's room and log out every session,
  so no administrator is needed and the name can be used again.

**Risk accepted:** whoever holds a machine's `account` (`PORELO_MATRIX_MACHINE_SECRET`) can derive the
password of every account that machine made - `@porelo.<machine>`, its relays and its tasks - and act as any
of them on the central homeserver. It gives no reach over another machine's accounts, which are derived from
that other machine's secret, nor over a person's account. Porelo keeps `account` in the machine's vault. A
machine whose `account` is lost cannot take its names back; the homeserver's administrator removes them, or
the machine takes another name.

**What would change the answer:** Porelo handing every task's kept secrets to `enroll` and `clear --project`
too, so each account could have an independent random password of its own; or Matrix offering scoped
provisioning credentials per machine namespace.
