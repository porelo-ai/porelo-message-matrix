# The local homeserver

One homeserver per account that runs Porelo, beside it on the same machine: Tuwunel in rootless podman,
reachable on `127.0.0.1`, and on one address of the machine's VPN only once it is
[published for another machine](#for-another-machine-on-the-vpn), not federating. The package `porelo-matrix-homeserver` installs it as a
systemd user unit, disabled. A project's first task start enables and starts it; by hand:

    systemctl --user enable --now porelo-matrix-homeserver

Without the package, that first start stops with "the unit porelo-matrix-homeserver is not installed for
this account". With lingering on, the homeserver comes back after a reboot without anybody logging in.

## Settings and data

| What | Where | Default |
|---|---|---|
| Port, on `127.0.0.1` only | `PORELO_MATRIX_PORT` in `~/.config/porelo/matrix/homeserver.conf`, under `$XDG_CONFIG_HOME` when it is set | the first free one from `8008` |
| Server name, part of every account id; fixed once the database exists | `PORELO_MATRIX_SERVER_NAME` in the same file | `localhost` |
| Registration tokens, this machine's first, then each admitted machine's; made on first start, readable by the account alone | `~/.config/porelo/matrix/tokens/registration-token`, under `$XDG_CONFIG_HOME` when it is set; the unit mounts the directory `tokens`, so a token added or revoked holds at once | - |
| Database | the podman volume `porelo-matrix-homeserver` | - |
| Backups, up to 48, made on `SIGUSR2`; the account's alone, since they hold every token and password hash | `~/.local/state/porelo/matrix/backups`, under `$XDG_STATE_HOME` when it is set | - |
| Container | `matrix-homeserver`, acted on only by the id in `$XDG_RUNTIME_DIR/porelo-matrix-homeserver.cid` | - |
| Address published for another machine, with its port | `PORELO_MATRIX_PUBLISH` in `homeserver.conf`, written by `publish` | none |
| The account's CA, made once; its certificate is another machine's `ca_file` | `ca.crt` and `ca.key` beside `homeserver.conf`, the key the account's alone | - |
| The server certificate for the published address, one year, renewed within its last 30 days | `tls/server.crt` and `tls/server.key` beside it, the only files mounted into the container | - |
| TLS settings the unit hands podman, written before every start; empty while nothing is published | `tls.env` beside it | - |

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
removes its own entries beside it. A published address goes with it: the socket closed, its drop-in, the CA, the server
certificate, both keys and `tls.env` removed. The package stays.

By hand, starting from an empty database takes three things; while any old one is left, the transport
stops and says the homeserver "was reset":

1. `systemctl --user stop porelo-matrix-homeserver` and `podman volume rm porelo-matrix-homeserver`;
2. what Porelo keeps of the transport: `porelo vault remove transport/matrix/account` and
   `porelo vault remove transport/matrix/project/<project>` for each project;
3. the transport's state: `rm -r ~/.local/state/porelo/transports/matrix`.

`tokens/registration-token` and `homeserver.conf` stay. The next task start registers `@porelo` again. On a host
whose helper admitted people's machines, their tokens and records go too, since the accounts they stood for went
with the database: `porelo-message-transport-matrix machine-token revoke <machine>` for each file in
`~/.config/porelo/matrix/machine-tokens/`, which takes its token out of the file the homeserver reads, then
`rm -r ~/.config/porelo/matrix/expelled-machines ~/.config/porelo/matrix/known-machines`.
A host using login accounts also removes `~/.config/porelo/matrix/expelled-logins` when its database is reset.

## Onto another machine

`backup <dir>` and `restore <dir>` ([the contract](transport.md#keeping-the-conversation)) carry the
conversation over: `backup` on the old machine while the homeserver runs, `restore` on the new one before the
first task start, then that start's `setup` takes the room back. Media are not in a backup.

The copy carries the account's CA, its key readable by the account alone, so a machine that names it as its `ca_file`
still trusts the restored homeserver; `restore` refuses a copy whose CA differs from one the account has already. The
published address and the server certificate name the old machine and stay behind: `restore` drops
`PORELO_MATRIX_PUBLISH`, and the new machine publishes its own address.

## For another machine on the VPN

Another machine's tasks talk in this machine's room when the homeserver is also served on this machine's address in a
VPN they share, over `https` with the account's own certificate:

    porelo-message-transport-matrix publish --address 192.0.2.2:8448

- **`--name <host name>`**, beside the address, names the host name the other machine reaches it by in the server
  certificate too, such as `matrix.example.org`, which that machine resolves to the published address and this one to
  loopback. One URL then serves both, so the published port is the homeserver's loopback port then.
- **It answers** `{"published": "192.0.2.2:8448", "ca_file": "<path of ca.crt>"}`, with `"name"` when one is given. The other machine names that CA
  file as its `ca_file`, and `https://192.0.2.2:8448` as its homeserver.
- **The address** is one literal address of this machine, IPv6 in brackets, with a port from 1024 on. A wildcard, a
  name, loopback or multicast is refused before anything changes, and so is a homeserver whose `homeserver.conf`
  names no `PORELO_MATRIX_PORT` yet: `publish` keeps the port a project's setup chose, or the one written there before
  the first start, which this machine's transport uses.
- **TLS:** Tuwunel's TLS covers each of its listeners and takes plain `http` beside `https`, so this machine's
  transport keeps its plain loopback port, and the published address forwards to that same port. The server certificate
  names the published address alone; the CA certificate is the one file another machine needs.
- **The socket** `porelo-matrix-homeserver-vpn.socket` listens on the address, from a drop-in under
  `~/.config/systemd/user/porelo-matrix-homeserver-vpn.socket.d/`, and hands each connection to
  `systemd-socket-proxyd`, which forwards it to the homeserver's loopback port. It binds the address before the VPN's
  tunnel gives it to the machine (`FreeBind`), so a tunnel that is down keeps neither the socket nor the homeserver
  down.
- **A registration token per machine:** `admit` ([the contract](transport.md#the-lifecycle)) gives each other machine
  a token of its own, kept under `machine-tokens/<machine>` beside the token file, which holds this machine's own
  token first. Tuwunel reads the file at every registration, so `dismiss` refuses a machine's token at once.
- **A certificate made elsewhere:** a host's administrator may place a server certificate signed by a CA whose key
  never reaches this machine: `ca.crt` beside `homeserver.conf` (`0644`), and `tls/server.crt` with its key
  `tls/server.key` (`0600`, in `tls/` of `0700`), all the account's own, and no `ca.key`. Each file holds exactly one
  certificate, no chain, and never a key beside it; the key is an unencrypted EC key in PKCS#8 (`BEGIN PRIVATE KEY`).
  Place them while the homeserver is stopped. Any one of them without a `ca.key` marks the certificate as made
  elsewhere: the transport then makes, renews and removes none of them, not on `publish --off`, `clear` or `restore`
  either. Every start and `publish` check, as a client does, that the certificate is signed by that `ca.crt`, a CA's
  certificate, that it is a server's (`serverAuth`, `digitalSignature` where it names usages), fits its key, names the
  published address and the name (`*.<domain>` covers one label, under two at least) and holds now, and refuse in
  words otherwise, before `publish` writes anything; in its last 30 days they say on stderr when it ends. A new one
  placed holds at the next start, `systemctl --user restart porelo-matrix-homeserver`. `backup` carries no placed
  CA, since it is placed again wherever the copy goes.
- **`publish --off`** closes the socket and removes its address, `PORELO_MATRIX_PUBLISH` and the server certificate,
  then restarts the homeserver without TLS. The CA stays, so a machine admitted later trusts the same file; a placed
  server certificate stays too.

## Legacy machine registration

On a host whose homeserver serves only people's machines in central mode, with no project of its own, the host
administrator's helper admits each person's machine and takes it out again:

    porelo-message-transport-matrix machine-token issue <machine>
    porelo-message-transport-matrix machine-token revoke <machine>
    porelo-message-transport-matrix machine-token expel <machine>

- **The administrator's token** comes on stdin among the secrets, `{"secrets": {"PORELO_MATRIX_ADMIN_TOKEN": "…"}}`,
  as Porelo hands it to `setup`. The first `issue`, given none, registers the homeserver's administrator `@porelo`
  before any machine has a token, since the first account registered is the administrator, and answers its token
  under `account` to be kept and handed back, even when that `issue` then fails: the answer comes on stdout beside
  the refusal. Once `@porelo` exists, nothing registers a second one.
- **`issue`** answers `{"machine": "<machine>", "token": "…"}`, the same token on every later call, and writes only
  the token files the homeserver reads and a record that the machine was issued one (`known-machines/<machine>`).
  It refuses a machine expelled or revoked before, a machine never issued a token whose accounts `@….<machine>` the
  homeserver lists already (their names are taken), and a machine whose accounts' names would end as a known
  machine's: one issued a token here or expelled, or one the homeserver lists by its `@porelo.<machine>`. A machine
  holding its token is answered it again, weighed when it was issued. What the files tell is checked before
  `@porelo` is registered.
- **`revoke`** answers `{"machine": "<machine>", "revoked": true|false}`; the homeserver refuses the token at its
  next registration. A revoked token logs no account out, so `expel` comes first; a machine revoked is issued no
  token again, and a token revoked records its machine as known, even one issued before that record was kept.
- **`expel`** records the machine as expelled and revokes its token at once, then deactivates every
  `@….<machine>`, `@porelo.<machine>` first, which ends its sessions and takes it out of every room, and answers
  `{"machine": "<machine>", "known": true, "revoked": true|false, "expelled": [ … ]}`. Tuwunel keeps a deactivated
  name taken, so the record (`expelled-machines/<machine>` beside the tokens) keeps the machine from a token again:
  the person comes back under another user name. A machine revoked before is expelled still. An account whose name
  ends as a longer machine's that was issued a token here (`@….x.<machine>` for `x.<machine>`) is that machine's
  and is left; no name a token's holder registers keeps `expel` from their own accounts. A machine of which nothing
  is known, with no token, no record and no account, is left as it is, so a name mistyped takes no name for good:
  it answers `"known": false` with exit 0, for a lock nothing to take away. A machine whose name ends as one issued
  a token here (`x.<machine>` while `<machine>` was) is refused (78) before all else, known or not; a name only
  expelled, which never held a token, refuses no machine's `expel`. The records are the host's and no backup holds
  them, so on another host restored from a backup, `expel` weighs only what that host issued.


The `.rpm`s install the same way. With SELinux enforcing, podman relabels the registration tokens'
directory for the container, and the account still reads it.

## A central homeserver

When a project shares a central homeserver, its settings name `homeserver` and `room` (an alias
`#local:server` or a room id). Every person uses `--login <login>`, kept by `setup` as `PORELO_MATRIX_LOGIN`,
and provisions only their own accounts: `@porelo.<login>`, `@porelo-relay-<project>.<login>` and
`@<task>.<login>`. Their own account is `@<login>`. A second machine uses that same login.

A login starts with a lower-case letter, contains only lower-case letters, digits and `-`, and is at
most 32 characters. It has no dot and is neither `porelo` nor a name beginning with `porelo-`; invalid logins
are refused with 78. On a host's gate (`*.localhost`) registration needs no client-held token; on an external
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
