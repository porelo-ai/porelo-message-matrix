# The local homeserver

One homeserver per account that runs Porelo, beside it on the same machine: Tuwunel in rootless podman,
reachable on `127.0.0.1` only, not federating. The package `porelo-matrix-homeserver` installs it as a
systemd user unit, disabled. A project's first task start enables and starts it; by hand:

    systemctl --user enable --now porelo-matrix-homeserver

Without the package, that first start stops with "the unit porelo-matrix-homeserver is not installed for
this account". With lingering on, the homeserver comes back after a reboot without anybody logging in.
The `.rpm`s install the same way. With SELinux enforcing, podman relabels the registration token's
directory for the container, and the account still reads it.

## Settings and data

`$XDG_CONFIG_HOME` and `$XDG_STATE_HOME` name the account's configuration and state folders; where one is not set, it is
the XDG default in the account's home directory.

| What | Where | Default |
|---|---|---|
| Port, on `127.0.0.1` only | `PORELO_MATRIX_PORT` in `$XDG_CONFIG_HOME/porelo/matrix/homeserver.conf` | the first free one from `8008` |
| Server name, part of every account id; fixed once the database exists | `PORELO_MATRIX_SERVER_NAME` in the same file | `localhost` |
| Registration token, made on first start, readable by the account alone | `$XDG_CONFIG_HOME/porelo/matrix/tokens/registration-token`; the unit mounts the directory `tokens` | - |
| Database | the podman volume `porelo-matrix-homeserver` | - |
| Backups, up to 96, made on `SIGUSR2`, two for each `backup`; the account's alone, since they hold every token and password hash | `$XDG_STATE_HOME/porelo/matrix/backups` | - |
| Container | `matrix-homeserver`, acted on only by the id in `$XDG_RUNTIME_DIR/porelo-matrix-homeserver.cid` | - |
| Environment file the unit hands podman, written before every start: empty, or in gate mode the homeserver's private socket | `tls.env` beside `homeserver.conf` | - |

The unit and `clear` stop and remove the container by the id podman gave the unit, never by its name. A
container somebody else named `matrix-homeserver` is neither replaced nor removed: the unit's start fails
and the journal says the name is in use.

Two accounts on one machine each get their own homeserver, on their own port.

The container is bounded whatever the machine's size: 512 processes, 1 GiB of memory, 2 CPUs and
65536 open files, with Tuwunel's database pool and caches capped to fit. So the account's user manager must delegate the `cpu`,
`memory` and `pids` controllers, as Ubuntu 26.04 does by default; where it does not, podman refuses the
limits and the homeserver does not start.

The first account registered is the server's administrator. It is Porelo's own, `@porelo`, which makes
the project's room and an account per task, and deactivates a task's account when the task is removed.
The image is pinned by digest in `homeserver/Dockerfile`; it is about 107 MB and is pulled at the first
start.

## Resetting it

`porelo clear` takes away everything here: the transport's `clear` stops and disables the unit and removes
its container, its database, its backups, `homeserver.conf`, the registration token, `tls.env`, the poll
positions, `expelled-logins`, the gate's `gate/` (its administrator's token and login tokens) and its socket
directory under `$XDG_RUNTIME_DIR`, and Porelo removes its own entries beside it. The package stays.

By hand, starting from an empty database takes three things; while any old one is left, the transport
stops and says the homeserver "was reset":

1. `systemctl --user stop porelo-matrix-homeserver` and `podman volume rm porelo-matrix-homeserver`;
2. what Porelo keeps of the transport: `porelo vault remove transport/matrix/account` and
   `porelo vault remove transport/matrix/project/<project>` for each project;
3. the transport's state: `rm -r "$XDG_STATE_HOME/porelo/transports/matrix"`, with the folder written out where the variable is not set.

`tokens/registration-token` and `homeserver.conf` stay. The next task start registers `@porelo` again. A
host behind its gate also removes `$XDG_CONFIG_HOME/porelo/matrix/expelled-logins` and `$XDG_CONFIG_HOME/porelo/matrix/gate`
when its database is reset, and runs `gate --prepare` again once the homeserver is up: until then the gate holds
the old database's administrator token and refuses every registration and sign-in.

## Onto another machine

`backup <dir>` and `restore <dir>` ([the contract](transport.md#keeping-the-conversation)) carry the
conversation over: `backup` on the old machine while the homeserver runs, `restore` on the new one before the
first task start, then that start's `setup` takes the room back. Media are not in a backup.

## A central homeserver

When a project shares a central homeserver, its settings name `homeserver` and `room` (an alias
`#local:server` or a room id). Every person uses `--login <login>`, kept by `setup` as `PORELO_MATRIX_LOGIN`,
and provisions only their own accounts: `@porelo.<login>`, `@porelo-relay-<project>.<login>` and
`@<task>.<login>`. Their own account is `@<login>`. A second machine uses that same login.

A login starts with a lower-case letter, contains only lower-case letters, digits and `-`, and is at
most 32 characters. It has no dot and is neither `porelo` nor a name beginning with `porelo-`; invalid logins
are refused with 78. A `unix:` or `*.localhost` homeserver is a host's gate, which registers by the caller's login and
never receives a token; on an external homeserver registration needs `PORELO_MATRIX_REGISTRATION_TOKEN` among the
secrets on its input.

The host's helper runs `porelo-message-transport-matrix expel <login>` as the homeserver's account, with its
administrator token among the secrets on stdin: behind the gate, the token in `gate/admin-token`. Like the gate's
service, it needs that account's `HOME`, `XDG_CONFIG_HOME` and `XDG_RUNTIME_DIR`, the directories of its user
homeserver unit. Behind the gate it reaches the running homeserver only through its private socket, and exits 75
while nothing answers there. It records a known login in `expelled-logins/<login>` beside
`homeserver.conf`, then deactivates the person's own account and every account whose final dot suffix is
that login, `@porelo.<login>` first. It never takes `@task.x-bob` for `bob`'s. The answer is
`{"login":"…","known":true|false,"expelled":[…]}`; running it again exits 0 with `known: true` and nothing
left to expel. An unknown login is left available. The gate refuses registrations for an expelled login;
the person returns under a new login. Locking a login or one project's access deactivates nothing: the gate
refuses a locked login, and the host's `keep-room` takes a person it no longer names out of the project's room.

What a person does on the central homeserver:

- **Make the project's room** from a Matrix client as invite-only (`join_rule: invite`, such as the
  `private_chat` preset, whose invite power level is 0) with guests forbidden (`m.room.guest_access` set to
  `forbidden`), and set its alias or room id as `room` in the project's settings.
- **On an external homeserver, issue each login a registration token** as the homeserver's administrator:
  `POST /_synapse/admin/v1/registration_tokens/new` with an administrator Bearer token. The person keeps it in
  their vault under `matrix-registration-token.<server>`, and Porelo lends it to `setup` and `enroll`. A host's gate
  needs none of this client-held token setup.
- **Invite each person's `@porelo.<login>` into the room.** The first `setup` registers it and prints
  `"admitted": false` until invited; the next `setup` joins the room, admits
  `@porelo-relay-<project>.<login>`, and prints `"admitted": true`. Central `join` remains refused (77);
  the host's gate supplies the person's own sign-in.

### Moving a project from a homeserver elsewhere

A project whose settings named `homeserver` without `room` is refused (78) by `setup` and `settings` until
`room` is set. Once `room` is set and a person invites `@porelo.<login>` into it, Porelo makes the login's new
accounts (`@porelo.<login>`, `@porelo-relay-<project>.<login>`, `@<task>.<login>`); the old accounts stay
on the homeserver until a person removes them.

## The host's homeserver behind its gate

A host shared by several people runs the homeserver as an account of its own. The host installs
`/run/porelo-matrix/client.sock`, owned by the homeserver account and group `porelo-people`, mode 0660,
in a directory of the same owner and group, mode 0750. The host keeps that group equal to the admitted,
unlocked logins and leaves the homeserver account out. A system socket unit with `Accept=yes` starts
`porelo-message-transport-matrix gate` as the homeserver account for each connection, with
`StandardInput=socket`. The gate requires that inherited connection to be a connected Unix socket;
starting it on ordinary stdin exits 78.

Run `porelo-message-transport-matrix gate --prepare` as the homeserver account before the homeserver's
first start. It preserves other settings in `$XDG_CONFIG_HOME/porelo/matrix/homeserver.conf`, sets
`PORELO_MATRIX_GATE=1` and `PORELO_MATRIX_INNER_PORT=8009`, and prepares private directories. Its stdout
is `{"gate":true,"administrator":false}` until the homeserver answers. Start the user unit, then run
`gate --prepare` again before admitting clients. It registers the first account, `@porelo`, with the
local registration token and keeps its administrator token in `gate/admin-token`, mode 0600. Its
answer then has `"administrator":true`; repeating preparation retains the account and token. If
`@porelo` already exists without an accepted token, preparation exits 78: restore the original token
to `gate/admin-token` with mode 0600 and repeat preparation. It never resets that account's password.
The system service needs this account's `HOME`, `XDG_CONFIG_HOME` and `XDG_RUNTIME_DIR`, pointing to
the same directories as its user homeserver unit.

Before every start, `homeserver-token` creates `$XDG_RUNTIME_DIR/porelo-matrix-gate` with mode 0700.
The unit mounts it at `/gate`; in gate mode `tls.env` sets
`TUWUNEL_UNIX_SOCKET_PATH=/gate/homeserver.sock` and `TUWUNEL_UNIX_SOCKET_PERMS=600`. The container's
TCP listener stays on 8008, while the host port forwards to unused container port 8009. No host TCP
port reaches that homeserver. The gate verifies the private directory and socket's ownership before
sending credentials to it. Its own homeserver calls use the transport's Unix-socket HTTP client.

A person forwards a local port to the host's client socket over SSH and points their Matrix client
at that port. SSH connects as that person's host login. The gate reads that user from `SO_PEERCRED`
and reads `/etc/group` at every request, including requests on an existing connection. A login starts
with a lower-case letter, contains only lower-case letters, digits and hyphens, has at most 32
characters, and is neither `porelo` nor a name starting `porelo-`. A login recorded under
`expelled-logins/<login>` beside `homeserver.conf` is refused too. A lock holds at the next request;
a sync already held open completes at its own timeout.

Clients see SSO named “Your login on this host” and token login. Password login is accepted but not
listed: the transport's accounts sign in with theirs, and nobody knows a person account's. SSO creates the
person's `@<login>` account if missing, under the same lock `keep-room` names accounts under, and an account made
meanwhile counts as there; it redirects to the client with a gate token valid for two
minutes, usable once by that host login alone. Only its SHA-256, login and expiry are kept, under
`gate/login-tokens/` with mode 0600. The token goes only to the homeserver's own clients: Element Desktop's
callback `element://vector/…`, or an `http` page at the forward the request came in on, its `Host`, which the
gate itself does not serve. A redirect anywhere else, a client listening on a loopback port of its own as some
desktop clients do among them, is refused with 400 and issues no token, and so is a request that carries an
`Origin`: a sign-in is a navigation, and a page that fetches it could read where it was sent. A navigation whose
`Sec-Fetch-Site` is other than `none` or `same-origin` is refused with 400 too, before any account is made: another
page started it, and `same-site` ignores ports. A client that sends no such header, as a desktop client's own browser
call may not, is taken. So a page elsewhere, or on another local port, that sends the person's browser to the gate
cannot take or start their sign-in. Registration offers the `m.login.dummy` authentication stage;
the gate creates an available account and signs it in with the supplied password. The caller may
register or use only `@<login>` or `@<name>.<login>`, where `<name>` starts with a lower-case letter
or digit and then contains lower-case letters, digits, underscores or hyphens. `@porelo` belongs to
no caller. Access tokens, in the header or query, are bound with `whoami`; each connection caches at
most 64 identities by token hash.

Only the client and media APIs pass. The gate answers discovery and sign-in flows, and hides admin,
federation and key APIs with 404. A browser client such as Element Desktop is served under CORS: the
gate answers its preflight without a token and names its origin in the headers of its own answers.
Only Element Desktop's origin `vector://vector` and a page at the forward the request came in on, `http://` and its
`Host`, are allowed; a request from any other origin, another local port or name and `https` among them, is refused
with 403. The gate takes the ssh peer's login as who asks, so a page elsewhere, or on another local site such as the
forge's web through the same tunnel, could otherwise read a sign-in answer or register a name of the person's and
read its token. Room aliases `porelo-<project>` are reserved: no client makes or takes one, and the host's helper keeps a project's room as the administrator (see [the transport](transport.md)). Refusal logs contain only the login, method, path without its query,
and status. The administrator token is read only for gate operations and is never returned to clients.

## Rehearsing a project's room on a host

Whether a host's tasks answer a person in the project's room as they should is rehearsed from the development machine,
as one of the host's people, through the gate:

    build/rehearse-busy-room.sh <login on the host> <host> <project> [<person>...]

- **It needs nothing typed in.** As `<login>` it forwards the host's `/run/porelo-matrix/client.sock`, signs in through
  the gate's sign-in as a browser does, finds the room `#porelo-<project>:<server>` in the homeserver's directory and
  the project's running tasks among its members: each `@porelo-<project>-<task>.<login>:<server>`, by the name the
  room shows. A stopped task has left the room and is not among them; two or more must run.
- **The project's other persons are named by their logins**, each a further `<person>`: their accounts and their
  helpers in the room are no stranger's. Any other member, or a line of one, fails the scenario of the room's order.
- **It prints the room and the tasks it found, then one line per scenario**, `OK`, `FAIL` with what was asked and
  what came back, or `SKIP`, and nothing of the session. Each task's record of what reached it is read with
  `porelo talk log` on the host.
- **Its exit is the rehearsal's:** 0 when every scenario held, 65 when one did not or fewer than two tasks run, 75 when
  the host, its gate or the forward could not be reached, 64 on a wrong call, 70 when the build failed. It closes the
  forward and leaves its direct chats and its session at the end, on a failure too.
- **ssh is the caller's**: its config, agent and known hosts decide how `<login>@<host>` is reached. The rehearsal is
  built from this checkout's tests and ships in no package.
- **A signal ends it without leaving**: on SIGINT or SIGTERM the JVM stops without logging the person out or leaving
  its direct chats. In the rented-host run that is the hard stop, and the rental is deleted after it.
- **The rented-host run's step**, `build/rehearse-steps/busy-room`, runs this call from a fresh clone of `main` in a
  scratch directory of its own, from `REHEARSAL_MATRIX_SOURCE` or the public repository, and writes in no checkout. It
  names as further persons everyone the host's `org status` answer has active in the project, and stops (2) without
  that answer.
