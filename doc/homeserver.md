# The local homeserver

One homeserver per account that runs Porelo, beside it on the same machine: Tuwunel in rootless podman,
reachable on `127.0.0.1` only, not federating. The package `porelo-matrix-homeserver` installs it as a
systemd user unit, disabled. A project's first task start enables and starts it; by hand:

    systemctl --user enable --now porelo-matrix-homeserver

Without the package, that first start stops with "the unit porelo-matrix-homeserver is not installed for
this account". With lingering on, the homeserver comes back after a reboot without anybody logging in.

## Settings and data

| What | Where | Default |
|---|---|---|
| Port, on `127.0.0.1` only | `PORELO_MATRIX_PORT` in `~/.config/porelo/matrix/homeserver.conf`, under `$XDG_CONFIG_HOME` when it is set | the first free one from `8008` |
| Server name, part of every account id; fixed once the database exists | `PORELO_MATRIX_SERVER_NAME` in the same file | `localhost` |
| Registration token, made on first start, readable by the account alone | `~/.config/porelo/matrix/registration-token`, under `$XDG_CONFIG_HOME` when it is set | - |
| Database | the podman volume `porelo-matrix-homeserver` | - |
| Backups, up to 48, made on `SIGUSR2`; the account's alone, since they hold every token and password hash | `~/.local/state/porelo/matrix/backups`, under `$XDG_STATE_HOME` when it is set | - |
| Container | `matrix-homeserver`, acted on only by the id in `$XDG_RUNTIME_DIR/porelo-matrix-homeserver.cid` | - |

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
its container, its database, its backups, `homeserver.conf`, the registration token and the poll positions, and Porelo
removes its own entries beside it. The package stays.

By hand, starting from an empty database takes three things; while any old one is left, the transport
stops and says the homeserver "was reset":

1. `systemctl --user stop porelo-matrix-homeserver` and `podman volume rm porelo-matrix-homeserver`;
2. what Porelo keeps of the transport: `porelo vault remove transport/matrix/account` and
   `porelo vault remove transport/matrix/project/<project>` for each project;
3. the transport's state: `rm -r ~/.local/state/porelo/transports/matrix`.

`registration-token` and `homeserver.conf` stay. The next task start registers `@porelo` again.

## Onto another machine

`backup <dir>` and `restore <dir>` ([the contract](transport.md#keeping-the-conversation)) carry the
conversation over: `backup` on the old machine while the homeserver runs, `restore` on the new one before the
first task start, then that start's `setup` takes the room back. Media are not in a backup.

## On Fedora

The `.rpm`s install the same way. With SELinux enforcing, podman relabels the registration token for the
container, and the account still reads it.

## A central homeserver

When a project's tasks run across several machines, or share one Tuwunel server over `https`, the project's
settings name `homeserver` and `room` (an alias `#local:server` or a room id). Each machine provisions only its
own accounts there (`@porelo.<machine>`, `@porelo-relay-<project>.<machine>`, `@<task>.<machine>`), and people
manage their own accounts on the homeserver (`join` is refused with 77).

What a person does on the central homeserver:

- **Make the project's room** from a Matrix client as invite-only (`join_rule: invite`, such as the
  `private_chat` preset, whose invite power level is 0) with guests forbidden (`m.room.guest_access` set to
  `forbidden`), and set its alias or room id as `room` in the project's settings.
- **Issue each machine its own registration token** as the homeserver's administrator:
  `POST /_synapse/admin/v1/registration_tokens/new` with an administrator Bearer token, and revoke a machine's
  token when that machine leaves with `DELETE /_synapse/admin/v1/registration_tokens/<token>`. Put each
  machine's token in the file named by `registration_token_file` on that machine, readable by that account
  alone (`chmod 600`).
- **Invite each machine's `@porelo.<machine>` into the room.** A machine's first `setup` registers
  `@porelo.<machine>` and prints `"admitted": false` while it is not yet invited; once a person in the room
  invites `@porelo.<machine>` (with the power to invite), the next `setup` joins the room, admits
  `@porelo-relay-<project>.<machine>`, and prints `"admitted": true`.

### Moving a project from a homeserver elsewhere

A project whose settings named `homeserver` without `room` is refused (78) by `setup` and `settings` until
`room` is set. Once `room` is set and a person invites `@porelo.<machine>` into it, the machine makes its new
accounts (`@porelo.<machine>`, `@porelo-relay-<project>.<machine>`, `@<task>.<machine>`); the old accounts stay
on the homeserver until a person removes them.
