# MX83 — The Homeserver's Data Survives A Volume Prune

**Status:** Idea.

**What must be true.** The homeserver's accounts, rooms and messages survive an account's routine podman clean-up,
such as `podman volume prune` run while the homeserver is stopped. On a machine that keeps its homeserver, a recent
copy is written without anybody asking for one, so a database that is lost anyway comes back from it.

## Why

- **The database lives in the named volume `porelo-matrix-homeserver`, which is in use only while the container
  runs.** The unit runs the container with `--rm`, so between a boot and the unit's start, or while it is stopped, no
  container holds the volume. `podman volume prune -f --filter until=168h` then removes it like any unused volume
  older than seven days. The next start makes an empty volume, and the homeserver creates a new database without
  a word: every account, every room and its history are gone, and every client's token answers 401.
- **This happened on a development machine.** A daily clean-up timer with `Persistent=true` caught up its missed run
  one second before the homeserver's unit started after a reboot.
- **The backups' directory stays empty unless `backup` is called.** The unit mounts `$XDG_STATE_HOME/porelo/matrix/backups`
  and has the homeserver make a snapshot on `SIGUSR2`. Only a host's playbook calls `backup`, every hour. A
  development machine never does, so there was no copy to restore from.

## To be checked

- Which way keeps the data out of a prune's reach on the pinned podman, measured with a stopped unit and the very
  prune command above:
  - a label on the volume that a prune excludes, which helps only with prunes that name that filter;
  - or a bind mount of a directory under `$XDG_STATE_HOME/porelo/matrix`, which no volume prune touches.
- How an existing volume's database moves to the new place on the first start, without loss, and what the transport's
  `backup` and `restore` and porelo-host's contract for them then read.
- What writes the copy on a machine without a host's playbook (a timer shipped with the unit, or the transport's
  own `backup` on a schedule), how many copies are kept, and what that costs in disk.
- That a start which finds no database where one was expected says so, rather than creating an empty one.
