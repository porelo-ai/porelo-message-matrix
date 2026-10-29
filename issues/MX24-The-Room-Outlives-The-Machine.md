# MX24 — The Room Outlives The Machine

**Status:** Review; the transport's part of keeping chosen data when a rented host goes.
**Size:** M - two new verbs and a change to `setup`, the unit's mounts, a contract porelo-host reads, and an
integration test with the real homeserver; no other repository changes.

**What must be true.** The local homeserver's conversation survives the machine it ran on: the transport writes a
consistent copy of the homeserver's database into a directory the caller names, and puts such a copy back into an
account that has no homeserver yet, so the rooms, their messages and the people's accounts come back as they were,
and no message the relay handed over before the copy is handed over again.

## Why

A host rented by the hour is deleted at `down`; what is not copied off it is gone. The project's plan keeps the
Matrix room among the data that outlives the machine, through a pair of commands per entry: one that writes the
entry's consistent copy into a directory, one that puts it back. Nothing in the transport takes a copy today.

## What was measured

On Tuwunel 1.9.4, the pinned image, in rootless podman on the host, with throwaway volumes:

- **`admin_signal_execute = ["server backup-database"]`** makes an online backup on `SIGUSR2`, with no admin
  room and no token. With `database_backup_path` bind-mounted from a host directory, the backup's files belong to
  the account; `meta/<id>` appears last, about 1 s after the signal for a room of 200 messages (1.4 MB).
- **The backup directory copied as it stands restores**: a one-off run with `--restore-backup --execute "server
  shutdown"` on an empty volume exits 0 in 0.6 s; the unit started on it afterwards has the room, every message and
  the accounts.
- **Every token valid at the copy is valid again after it**: the tokens are in the database.
- **`--restore-backup` over a database that has data replaces it without a word**, exit 0.
- **`users reset-password <user> [<password>]`** takes the password as an argument. Given in
  `TUWUNEL_ADMIN_EXECUTE` with `"server shutdown"` after it, inherited from podman's own environment, the run resets
  it and ends; its reply, password included, goes to the run's standard output, so that output is discarded and the
  run has `--log-driver=none`, which keeps it out of the journal. The tokens of the account's other sessions stay
  valid. A run with `--network=none` does not start: Tuwunel needs a resolver. A config file read from `-c /dev/stdin`
  did not run its `admin_execute`, and the run did not end.

## Plan

- **The unit** (`homeserver/porelo-matrix-homeserver.service`): `-v %S/porelo/matrix/backups:/backup:Z`, with
  `TUWUNEL_DATABASE_BACKUP_PATH=/backup`, `TUWUNEL_DATABASE_BACKUPS_TO_KEEP=48` and
  `TUWUNEL_ADMIN_SIGNAL_EXECUTE=["server backup-database"]`. `homeserver-token`, already its `ExecStartPre`, makes
  the directory, mode 700, since a backup holds every token and password hash. Media stay out, as in every backup.
- **`backup <dir>`**, a new class `Keep`: refused (78) without this account's installed unit or with `<dir>` missing
  or not empty, 75 when the unit is not active or no backup appears in time; a second `backup` waits for the first,
  never fails on it. It takes `keep.lock`, copies the poll positions and references, then signals the unit's
  container by its cid file (`podman kill --signal USR2 --cidfile=…`), waits up to 60 s for a new `meta/<id>` (75
  past it), and copies the backup directory, the positions and `homeserver.conf` into `<dir>`. A position taken before the
  backup never points past it, so after a restore the relay hands over exactly what came after its last poll.
  Prints `{"backup": <id>}`.
- **`restore <dir>`**: where the volume `porelo-matrix-homeserver` exists, it restores nothing, leaves the database
  untouched and answers 0 with `{"restore": "present"}`, so a second `up` on the same host goes on. It is refused
  (78) when the unit is active without that volume, when `<dir>` holds no whole backup, or when a `homeserver.conf`
  here names another port or server name (the poll positions are keyed by the URL, the account ids by the name). It writes
  `homeserver.conf` and the positions, runs the image once on a new volume with `--restore-backup --execute "server
  shutdown"`, and leaves the marker `restored` in the state directory. It does not start the unit. Prints
  `{"restore": <id>}`.
- **`setup` after a restore**: Porelo's vault on the new host holds no token of the transport's, and `@porelo`
  exists. With the marker present and the local homeserver, `setup` takes `@porelo` back instead of refusing: the
  unit stopped, one run of the image with `users reset-password porelo <fresh>` in `TUWUNEL_ADMIN_EXECUTE`, which
  podman passes on from its own environment (`-e NAME` with no value), never on a command line; then the unit started,
  a login, every other session of it logged out, and the marker removed. The relay and the tasks are taken back as
  `setup` and `enroll` take an account whose token is gone today, and their other sessions are logged out the same
  way, so no token restored with the copy is valid. The people keep their passwords. Without the marker, the refusal
  stays as it is.
- **The one-off runs** take the image from the installed unit (`systemctl --user cat`), so they run the version the
  unit runs, with the unit's database and backup directory, registration off, no port published, and their output
  discarded.
- **`poll` holds a shared lock** on `keep.lock` in the state directory for its run, and `backup` holds it exclusively
  from the positions' copy to the end of its own, so no position moves between the copy and the backup, and a second
  `backup` waits for the first.
- **`clear`** removes the backup directory and the marker too.
- **One seam, `Unit`**: what the verbs ask of the homeserver - installed, active, start, stop, signal, a database,
  a one-off run - is an interface; `PodmanUnit` is the unit through `systemctl --user` and podman, and the tests'
  `ProcessUnit` runs the image's own binary as a process, so `KeepIT` needs neither systemd nor a container start.
- **Not lifecycle verbs**: Porelo never runs them, so `describe` does not list them; `doc/transport.md` gets a section
  *Keeping the conversation* for porelo-host's playbook, which runs `backup` and `restore`,
  `doc/homeserver.md` the backup directory.
- **Red first**, `KeepIT` on the real homeserver: a room with messages, a relay poll, a message more, `backup`, the
  account cleared, `restore`, `setup` and the relay's `poll` with an empty vault: the room's messages are there, and
  only the one after the poll is handed over. Without `restore`, `setup` makes a new room and the messages are gone.
  `KeepTest`: `restore` leaves an existing volume unchanged; `backup` refuses a directory not empty.

## Acceptance

- `KeepIT` green on the VM, seen red without the restore and with the positions taken after the signal.
- The round trip once by hand on the VM with the package's own unit, through `PodmanUnit`: `backup`, `clear`,
  `restore`, `setup`, and the room's messages read.
- A `restore` into an account with a database restores nothing and answers `present`, the database unchanged.
- No token or password in a command line, a log line or a file while `setup` takes `@porelo` back: the run's
  `/proc/<pid>/cmdline` and the journal read for it.
- `doc/transport.md` states both commands' arguments, output and exit codes, as porelo-host's playbook needs them.

## To be checked

- How long `backup` takes with a room of a few weeks' messages, on the VM.
