# MX24 — The Room Outlives The Machine

**Status:** Idea; the transport's part of porelo-project's
[PJ45](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj45), which keeps chosen data when a
rented host goes.

**What must be true.** The local homeserver's conversation survives the machine it ran on: the transport writes a
consistent copy of the homeserver's database into a directory the caller names, and puts such a copy back into a
homeserver that is stopped, so the rooms, their messages and the accounts come back as they were.

## Why

A host rented by the hour is deleted at `down`; what is not copied off it is gone. The project's plan keeps the
Matrix room among the data that outlives the machine, through a pair of commands per entry: one that writes the
entry's consistent copy into a directory, one that puts it back. Nothing in the transport takes a copy today.

## What the account's homeserver keeps, and where

- **The database:** the podman volume `porelo-matrix-homeserver`. This is what the copy is of.
- **Its settings:** `porelo/matrix/homeserver.conf` (port, server name) and `porelo/matrix/registration-token`
  under `$XDG_CONFIG_HOME`; the server name is fixed once the database exists, so a restore needs the same one.
- **The poll positions:** under `$XDG_STATE_HOME/porelo/transports/matrix`; positions from before a restore may
  point past what the restored database holds.
- **Not here:** the accounts' tokens, which are in Porelo's vault, never in a file of the transport's. The vault
  itself is not copied; how the administrator's, the relay's and the tasks' tokens are there again after a
  restore is to be checked.

## The shape the project plans

`backup <dir>` gives Tuwunel's database-backup command in the admin room the transport already uses (`AdminRoom`),
waits for the answer and copies the new backup into `<dir>`; `restore <dir>`, with the unit stopped, copies
`<dir>` back and runs the restore once. The unit mounts a second volume as `TUWUNEL_DATABASE_BACKUP_PATH`, keeps
`database_backups_to_keep = 48`, and leaves media out.

## To be checked

- Tuwunel's admin command for an online backup at the pinned 1.9.4, its answer, and where it writes; and how a
  restore is run with the unit stopped.
- Whether the copy needs the server name and registration token beside it, and whether `restore` refuses a copy
  made under another server name.
- What happens to the poll positions and the relay after a restore: kept, reset, or refused.
- Whether `backup` and `restore` are lifecycle verbs Porelo runs, or commands only the host's playbook calls,
  and what `describe` says of them.
- Whether the tokens kept in Porelo's vault still hold against a restored database, and what Porelo does when
  they are gone with the old host: register again, or refuse with the reset message `setup` gives today.
- The order on a host: porelo's `vault clear --yes` removes the account's own homeserver with its database
  volume, so at `down` the copy is taken before it; at `up` the restore comes before the first task start, whose
  `setup` would otherwise make a fresh homeserver with a new `@porelo`.
