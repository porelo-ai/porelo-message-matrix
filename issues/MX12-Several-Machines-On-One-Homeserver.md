# MX12 — Several Machines On One Homeserver

**Status:** Backlog; waits for the operator's answers on its central mode, asked in the channel.
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
- **`enroll`, central**: registers `@<task>.<machine>`, invites it with the machine's account, and prints
  its password in `secrets` beside its token, so the task's own account can be deactivated later. Porelo keeps
  `enroll`'s `secrets` in the vault per task and hands them back to `retire` as environment variables,
  forgetting them only once `retire` succeeds; a task with no kept secrets gets no `retire` call, so a
  central `enroll` always prints them.
- **`retire`, central**: as the task's own account, `POST /_matrix/client/v3/account/deactivate` with the
  password under user-interactive auth (`MatrixClient`); no `AdminRoom`. Enrolled again, the task's
  password changes through `/account/password`, also as itself.
- **`join`, central**: 77, "people are managed at the homeserver itself". **`clear`, central**: deactivates
  this machine's accounts only and leaves the room and other machines' accounts alone.
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
- **The modes:** the account's own loopback homeserver and a homeserver elsewhere that one machine provisions
  keep today's paths byte for byte. The central mode's choice of switch is under the operator's question in the
  channel; `LifecycleIT` names a `homeserver` in every test today.
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
- porelo's `vault clear` of a project on the homeserver elsewhere deactivates its accounts there - a person's
  password login, taken before, is refused after it - and leaves Porelo's other accounts and every other project
  there alone; a new vault and a start in the project then succeed. Walked on a rented machine. The token reaches
  the transport through `registration_token_file`.

## To be checked

- Whether Tuwunel lets an account deactivate itself and change its own password over the client API with
  password user-interactive auth, as the plan's `retire` needs. Measured against the pinned image; mine.
- Whether a member given the power to invite, not an administrator, is enough to invite the relay and the
  tasks into a room a person made. Measured against Tuwunel; mine.
- Whether Tuwunel takes several registration tokens, so each machine could have one of its own; the settings'
  `registration_token_file` is read today, by `elsewhereToken`.
