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

| What | Where | Default |
|---|---|---|
| Port, on `127.0.0.1` only | `PORELO_MATRIX_PORT` in `~/.config/porelo/matrix/homeserver.conf`, under `$XDG_CONFIG_HOME` when it is set | the first free one from `8008` |
| Server name, part of every account id; fixed once the database exists | `PORELO_MATRIX_SERVER_NAME` in the same file | `localhost` |
| Registration token, made on first start, readable by the account alone | `~/.config/porelo/matrix/tokens/registration-token`, under `$XDG_CONFIG_HOME` when it is set; the unit mounts the directory `tokens` | - |
| Database | the podman volume `porelo-matrix-homeserver` | - |
| Backups, up to 48, made on `SIGUSR2`; the account's alone, since they hold every token and password hash | `~/.local/state/porelo/matrix/backups`, under `$XDG_STATE_HOME` when it is set | - |
| Container | `matrix-homeserver`, acted on only by the id in `$XDG_RUNTIME_DIR/porelo-matrix-homeserver.cid` | - |
| Environment file the unit hands podman, written before every start: empty, or in gate mode the homeserver's private socket | `tls.env` beside `homeserver.conf` | - |

The unit and `clear` stop and remove the container by the id podman gave the unit, never by its name. A
container somebody else named `matrix-homeserver` is neither replaced nor removed: the unit's start fails
and the journal says the name is in use.

Two accounts on one machine each get their own homeserver, on their own port.

The container is bounded whatever the machine's size: 512 processes, 1 GiB of memory and 2 CPUs, with
Tuwunel's database pool and caches capped to fit. So the account's user manager must delegate the `cpu`,
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
3. the transport's state: `rm -r ~/.local/state/porelo/transports/matrix`.

`tokens/registration-token` and `homeserver.conf` stay. The next task start registers `@porelo` again. A
host behind its gate also removes `~/.config/porelo/matrix/expelled-logins` and `~/.config/porelo/matrix/gate`
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
are refused with 78. On a host's gate (`unix:` or `*.localhost`) registration needs no client-held token; on an external
homeserver it still needs `registration_token_file` or `PORELO_MATRIX_REGISTRATION_TOKEN`.

The host's helper runs `porelo-message-transport-matrix expel <login>` as the homeserver's account, with its
administrator token among the secrets on stdin. It records a known login in `expelled-logins/<login>` beside
`homeserver.conf`, then deactivates the person's own account and every account whose final dot suffix is
that login, `@porelo.<login>` first. It never takes `@task.x-bob` for `bob`'s. The answer is
`{"login":"…","known":true|false,"expelled":[…]}`; running it again exits 0 with `known: true` and nothing
left to expel. An unknown login is left available. The gate refuses registrations for an expelled login;
the person returns under a new login. Locking a login or one project's access deactivates nothing: the gate
refuses a locked login, and `members` removes persons absent from that project's access list.

What a person does on the central homeserver:

- **Let the project admin's Porelo make the room and keep it:** `setup --login <login> --create-room` makes
  `#porelo-<project>:<server>` as `@porelo.<login>`. `members --project <project> --persons alice,bob` invites
  each listed person's bare account and provisioning account. It removes every account of a login the list
  drops from that room, preserving other logins and the maker. Running it again with the same list changes
  nothing ([the contract](transport.md#the-lifecycle)). The project's settings name that alias as `room`.
- **Make the project's room** from a Matrix client as invite-only (`join_rule: invite`, such as the
  `private_chat` preset, whose invite power level is 0) with guests forbidden (`m.room.guest_access` set to
  `forbidden`), and set its alias or room id as `room` in the project's settings.
- **On an external homeserver, issue each login a registration token** as the homeserver's administrator:
  `POST /_synapse/admin/v1/registration_tokens/new` with an administrator Bearer token. Put the token in the
  file named by `registration_token_file`, readable by that account alone (`chmod 600`). A host's gate needs
  none of this client-held token setup.
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
person's `@<login>` account if missing and redirects to the client with a gate token valid for two
minutes, usable once by that host login alone. Only its SHA-256, login and expiry are kept, under
`gate/login-tokens/` with mode 0600. Registration offers the `m.login.dummy` authentication stage;
the gate creates an available account and signs it in with the supplied password. The caller may
register or use only `@<login>` or `@<name>.<login>`, where `<name>` starts with a lower-case letter
or digit and then contains lower-case letters, digits, underscores or hyphens. `@porelo` belongs to
no caller. Access tokens, in the header or query, are bound with `whoami`; each connection caches at
most 64 identities by token hash.

Only the client and media APIs pass. The gate answers discovery and sign-in flows, and hides admin,
federation and key APIs with 404. A browser client such as Element Desktop is served under CORS: the
gate answers its preflight without a token and names its origin in the headers of its own answers.
Only Element Desktop's origin `vector://vector` and pages served from loopback or a `*.localhost`
name are allowed; a request from any other origin is refused with 403, so a page elsewhere cannot
read a sign-in answer the person's browser was made to ask for. Reserved room aliases `porelo-<project>` require the token of
`@porelo.<login>` and that login on a line of `/etc/porelo-host/matrix/<project>.admins`; a missing
or empty file grants nothing. Refusal logs contain only the login, method, path without its query,
and status. The administrator token is read only for gate operations and is never returned to clients.
