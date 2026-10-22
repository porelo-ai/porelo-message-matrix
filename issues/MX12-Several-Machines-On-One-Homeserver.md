# MX12 — Several Machines On One Homeserver

**Status:** Implementing.
**Size:** L - every lifecycle verb gets a second, central mode, `describe` and the settings change a contract
Porelo reads, and its acceptance needs two machines on one homeserver.

**What must be true.** A project whose tasks run on several machines has one conversation, in one room on
one central homeserver, where a task on one machine messages a task on another and a person sees all of
them. Each machine provisions only its own accounts there, and no machine holds a secret that lets it act
on another machine's accounts.

## Why

- **The transport reaches a named homeserver already:** `homeserver` in the settings, `https` with a
  public, an intranet or a self-signed certificate, registration with the homeserver's registration token
  (`PORELO_MATRIX_REGISTRATION_TOKEN`), `--loopback-only` for an offline project.
- **But only one machine can provision there.** `setup` registers `@porelo` as the first account, which
  Tuwunel makes its administrator, and prints its token for Porelo to keep. A second machine running `setup`
  against the same homeserver is refused (78): `@porelo` exists, and that machine holds no token for it.
- **What the administrator is needed for:** `retire` deactivates a task's account, and `join --reset` or a
  task enrolled again sets a password - both only through the admin room (`!admin users …`), since Tuwunel
  offers no HTTP call for either. Registering an account, making the room and inviting need no
  administrator: the registration token and the room's power levels are enough.
- **The room is made by whoever runs `setup` first**; another machine's accounts join it only when a
  member with the power to invite invites them.
- **Porelo's side is built, and dormant** until `describe` lists `"takes": ["machine"]`: it passes
  `--machine`, records `machine`, `admitted`, `address` and `room` from `setup`, starts no task while
  `admitted` is false, and runs `setup` without a task through `SetUpMessages`.

## The shape

- **On a central homeserver, people are administered outside Porelo.** A person creates the project's room,
  invites each machine into it, and manages their own account. Porelo makes no room there and does not
  `join` a person: `join` answers 77, "people are managed at the homeserver itself".
- **The room is named in the project's settings**, `room`: an alias or a room id. `setup` refuses (78) a
  central homeserver without it, and `settings` checks it.
- **Each machine registers its own accounts** with the homeserver's registration token: `@porelo.<machine>`,
  its tasks' `@<container>.<machine>` and its relay `@porelo-relay-<project>.<machine>`.
- **A person admits a machine** by inviting `@porelo.<machine>` from their Matrix client and giving it the
  power to invite; there is no `admit` verb. Until then `setup` exits 0 with its `account` secrets,
  `"machine"` and `"admitted": false`, and no `conversation`, so the account it registered is kept; once
  invited it joins and prints `"admitted": true` and the room.
- **No administrator on a central homeserver.** `enroll` prints each task's password beside its token;
  `retire` and enrolling a task again act as the task's own account through the client API. A machine
  reaches only the accounts it made.
- **`--machine <name>`** on every lifecycle verb, announced by `describe` as `"takes": ["machine"]`: the
  host name, sanitized, kept in `setup`'s `account` and printed as `"machine"`; a name another machine
  holds on that homeserver is refused (78).
- **The account's own homeserver on loopback stays as it is**: `@porelo` its administrator, the room made by
  `setup`, `join` and `--reset` as today.
- **`doc/homeserver.md` says how to run the central homeserver**: where, TLS, the registration token, and
  the person's steps - make the room, invite each machine.

## Plan

- **Settings and its check** (`Settings`, `SettingsCheck`): a `room` key, an alias or a room id. On a
  central homeserver (`homeserver` set) it is required. Red first in `SettingsCheckTest` and `LifecycleIT`: `setup` without `room` on a central homeserver exits 78.
  `SettingsTest` and `SettingsCheckTest` hold today that `homeserver` alone is valid, which stays so for a homeserver
  elsewhere that one machine provisions.
- **The option** (`Lifecycle.Options`): `--machine <name>` on every verb, sanitized to the localpart
  alphabet; `describe` (`Describe.run`) adds `"takes": ["machine"]`, which wakes Porelo's dormant side.
  Red first in `DescribeTest` and `LifecycleIT.badArguments` (usage).
- **`setup`, central** (`Lifecycle.setup`): no `admin(...)`, no `AdminRoom`, no `room(...)`. It registers
  `@porelo.<machine>` with the registration token (`Accounts.register`); a name another machine holds is
  78. If that account is invited to `room`, it joins and prints `"admitted": true` and the room as
  `conversation`; if not, `"admitted": false` and no `conversation`. The relay is
  `@porelo-relay-<project>.<machine>`, invited by the machine's account.
- **The machine's secret:** `setup` makes a random secret for the machine once and prints it in `account`
  (`PORELO_MATRIX_MACHINE_SECRET`), which Porelo hands to every lifecycle verb. Every account the machine makes -
  its relay and each task - has as password an HMAC-SHA256 of that secret over the account's localpart. Read in
  porelo's `TransportLifecycle`: `enroll` never gets a task's earlier map, and `clear` gets no task's map at all, so
  a password printed once would be lost to both; one derived from the machine's secret is not. Another machine
  holds another secret and reaches none of these accounts.
- **`enroll`, central**: registers `@<task>.<machine>` with the derived password, or, the account there already,
  logs in with it - a token lost is a token taken again, and every other session is logged out; invites it with
  the machine's account. `@<task>.<machine>` in another project's room is refused (78), as on the own homeserver.
- **`retire`, central**: logs in as the task with the derived password and deactivates itself
  (`/account/deactivate` with password user-interactive auth); no `AdminRoom`. Run again, the login is
  `M_USER_DEACTIVATED`, which is done, not a failure.
- **`join`, central**: 77, "people are managed at the homeserver itself". **`clear --project`, central**:
  deactivates the relay and every `@<…>.<machine>` in the room that is in no other project's room, each as
  itself with its derived password; leaves `@porelo.<machine>`, the room, people and other machines' accounts.
  So a task Porelo forgot without a `retire` is still taken away.
- **Read against today's code, the central mode must also answer** what was built since its estimate:
  - `enroll` and `retire` refuse a task account in another project's room through `membersElsewhere`, which reads
    `@porelo`'s joined rooms and keeps those named `#porelo-*`. Centrally there is no `@porelo`, a person's room may
    have another alias, and a task is `@<task>.<machine>`: the check reads `@porelo.<machine>`'s rooms instead.
  - `clear --project` keeps a member of another project's room and deactivates through `AdminRoom`. Centrally it
    deactivates this machine's relay and tasks as themselves, with the passwords Porelo kept, and leaves
    `@porelo.<machine>` while another project of the machine uses it.
  - `ours` takes a room only if `@porelo` made it. Centrally a person made it, so the check keeps its other two
    conditions, invite-only and guests forbidden, and drops the creator's.
  - `Listener` checks only plain `http` on loopback, so a central `https` homeserver passes it; the two-VM
    acceptance names the central homeserver's CA with `ca_file`.
  - `backup`, `restore` and `clear` without `--project` stay the account's own local homeserver's.
- **The modes, decided:** a `homeserver` in the settings is always the central one; the mode where one machine
  provisions a homeserver elsewhere as its administrator ends. The account's own loopback homeserver keeps today's
  paths byte for byte. What follows:
  - `Settings`, `SettingsCheck`: `homeserver` without `room` is refused (78), where `SettingsTest` and
    `SettingsCheckTest` hold it valid today.
  - `LifecycleIT` names a `homeserver` in every test: its loopback-mode tests move to a `Unit` that starts a test
    homeserver as the account's own, and the central tests take `room` and `--machine`.
  - `doc/transport.md`, `doc/homeserver.md` and `doc/decisions.md` drop the homeserver elsewhere with its
    administrator; the changelog names the change under *Changed*, with what a project on such a homeserver does
    now: name its room, invite each machine, and enroll its tasks again.
  - `clear --project`, central: this machine's relay and tasks deactivate themselves; the room, other machines'
    and people's accounts stay. People are managed at the homeserver.
  - A room a person made is taken when it is invite-only with guests forbidden; the creator check goes.
  - **One registration token per machine**, each in that machine's `registration_token_file`. Measured on the
    pinned image: a token issued by the homeserver's administrator (`POST /_synapse/admin/v1/registration_tokens/new`)
    registers, the configured one still does, a wrong or revoked one is `M_FORBIDDEN`. Issuing and revoking a
    machine's token is the homeserver's administrator's, a person's, and `doc/homeserver.md` says how.
- **Tests:** `LifecycleIT` against one Tuwunel named as `homeserver`, two `--machine` names standing for
  two machines: each refusal of the acceptance seen red first there. Then the two-machine acceptance on
  two local VMs, the test VM and a second one beside it, with the central homeserver on one of the two;
  nothing runs on the operator's own machines.
- **Docs:** `doc/transport.md` (the verbs' central mode, `--machine`, `room`), `doc/homeserver.md` (running
  the central homeserver, a person's steps), `doc/decisions.md` (no administrator on a central homeserver).

## Acceptance

- Across two machines, the Ubuntu test VM and a second local VM, tasks on both in one project
  message each other, and a person in the room sees all of them.
- A task retired on one machine is gone, and the other machine's tasks and accounts are untouched.
- Seen to fail:
  - one machine acting on another machine's account (`retire`, or enrolling its task again) is refused;
  - `setup` without `room` on a central homeserver is refused with 78;
  - a `--machine` name another machine holds is refused with 78;
  - `join` on a central homeserver answers 77;
  - a machine not yet invited starts no task.
- porelo's `vault clear` of a project on the central homeserver deactivates this machine's accounts there and
  leaves the room, people's and other machines' accounts alone; a new vault and a start in the project then
  succeed. Walked on a rented machine. The machine's own token reaches the transport through
  `registration_token_file`.

## Measured

Against the pinned Tuwunel, in a container of its own on the host:
- An account changes its own password (`/account/password`) and deactivates itself (`/account/deactivate`), each
  with password user-interactive auth and no administrator. Deactivated, its login is `M_USER_DEACTIVATED`, and it
  has left the room.
- In a room a person made with the preset `private_chat`, an invited and joined machine may invite its tasks at
  once: the preset's invite level is 0. The person's steps in `doc/homeserver.md` include forbidding guests, which
  the room check asks for; what the preset sets for guests is read when it is built.
- Several registration tokens: see *The modes, decided*.
