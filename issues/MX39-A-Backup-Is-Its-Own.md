# MX39 — A Backup Is Its Own

**Status:** Idea.

**What must be true.** A copy `backup <dir>` reports as made holds the homeserver's database and the poll positions
of that one backup, so a restored relay hands over exactly what came after its last poll; and no backup ever empties
a directory another backup filled.

## Why

- **A late completion is taken for the next backup's.** `backup` signals the homeserver, then waits for a newer
  file in the backups' `meta` directory than the newest it saw before (`Keep.awaitBackup`, `Keep.newest`). A first
  backup that gave up waiting, or died, leaves its snapshot running. A poll then moves its position on. A second
  backup copies the newer positions and takes the first one's late snapshot as its own. Restored, those positions
  point past the database, and the messages in between are never handed over.
- **Two backups into one directory.** `backup` checks that the target is empty before it takes the lock. Two runs
  into the same empty directory both pass; the first fills it, and the second, finding files, fails and empties
  the directory (`Keep.emptyAgain`), the first one's copy with it. One run then reported success with nothing kept.
- The host's playbook runs `backup` every hour and at `down`
  ([HO07](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho07)), so a run that gives up and
  the next one meeting is not far-fetched.

## Measured

- A review reproduced both against the code with simulated completions and files: the second backup accepted the
  first one's late `meta` entry, and the second run into one directory left it empty after the first had reported
  its copy. Neither was run against the pinned Tuwunel.

## Acceptance

- A backup takes as its completion only the snapshot its own signal started, seen to fail with a late earlier one.
- A second `backup` into a directory the first one is filling leaves the first one's copy whole, seen to fail today.

## To be checked

- Whether the pinned Tuwunel names a snapshot so that it can be tied to the signal that started it, or the backup
  must drain earlier snapshots before it copies the positions; read in its documentation and measured on a test VM.
- Whether checking the target's emptiness again under the lock is enough, or each run must copy into a directory of
  its own and move it into place.
