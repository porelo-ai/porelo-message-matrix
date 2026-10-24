# MX39 — A Backup Is Its Own

**Status:** Analyzed.
**Size:** S - one class, `Keep`, and its tests; no contract another repository reads.

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

- A review reproduced both against the code with simulated completions and files.
- **On a test VM, against the pinned Tuwunel, with the built transport and the unit in the account's own user
  directory:**
  - **Two backups into one empty directory at once: reproduced.** One ended 78 ("cannot copy into … database/meta/2"),
    the other 0 with `{"backup":2}`, and the directory was empty afterwards: the first one's clean-up removed the
    copy the second had reported.
  - **Two signals 0.05 s apart made two snapshots, in signal order, 0.1 s apart.** The journal names each only as
    `Created database backup backup_id=N`, not which signal asked for it.
  - **A backup killed right after its signal, then a second one at once:** the second took its own snapshot (7),
    since the first one's (6) was already done. With a database of about 1 MB a snapshot takes about 10 ms, so the
    late completion did not happen at this size. It needs a snapshot still running when the next backup starts.

## Plan

- **A backup drains before it copies.** Holding both locks, it signals once and waits for a new snapshot: since the
  homeserver makes snapshots in signal order, every snapshot asked for before, a dead backup's too, is done once
  this one is. Only then does it copy the positions, signal again, and take the next snapshot as its own.
- **The target is checked again under the lock.** A target that is no longer empty is refused with 78 and left as
  it is; on a failure a backup removes only the files it copied, never the directory's earlier content.
- **`TUWUNEL_DATABASE_BACKUPS_TO_KEEP`** (48 in the unit) then holds 24 hours of hourly backups instead of 48, since
  each backup leaves two snapshots: see the open question.
- **Red first:** `KeepTest`, a fake unit whose snapshots complete in signal order and an earlier signal's snapshot
  completing after the next backup started: today the second backup reports the earlier one's id; and two backups
  into one directory, the second started while the first holds the lock: today the target ends empty.
- Files: `Keep.java`, `KeepTest.java`, `KeepIT.java`, `homeserver/porelo-matrix-homeserver.service` if the question
  is answered so, `CHANGELOG.md`.

## Open questions

- Two snapshots per backup: keep 48 snapshots, so 24 hours of hourly backups, or raise it to 96 so 48 hours stay?
  RocksDB's backups share their unchanged files, so the second snapshot costs little space. Proposed: 96.

## Acceptance

- A backup takes as its completion only the snapshot its own signal started, seen to fail with a late earlier one.
- A second `backup` into a directory the first one is filling leaves the first one's copy whole, seen to fail today.

## To be checked

- That snapshots stay in signal order with a database large enough that one takes seconds, measured on a test VM
  while the build is checked.
