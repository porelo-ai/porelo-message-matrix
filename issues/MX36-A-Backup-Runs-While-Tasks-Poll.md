# MX36 — A Backup Runs While Tasks Poll

**Status:** Implementing.
**Size:** S - one module (`Keep`, `Poll`) and its tests, no contract other repositories read.

**What must be true.** `backup <dir>` succeeds within its bound while Porelo's poll loops run, so the host's
playbook copies the conversation off a busy host without stopping the tasks. Polls lose no message and never
hang on a backup: a poll held off answers 75 at once and Porelo polls again, as today, and a backup that hangs
still hangs no poll.

## Why

- `backup <dir>` ([MX24](README.md#mx24), for the host's playbook, which keeps the conversation across a rented
  host) takes `keep.lock` in the poll state directory exclusively, retrying `tryLock()` every 100 ms for up to
  180 s (`Keep.LOCK_WAIT`), then exits 75 with `"a poll or another backup held keep.lock for 180 s"`.
- Every `poll` holds `keep.lock` shared for its whole run, the long sync of `--wait` (up to 30 s,
  `Poll.MAX_WAIT_SECONDS`) included (`Poll.java`, the `try` block around `Keep.shared`). A poll refuses with 75
  at once when a backup holds it.
- Porelo runs polls back to back: one `poll --persons --wait 30` loop for the relay, and one
  `poll --direct --wait 30` loop per task. Their shared holds overlap, so `keep.lock` is never free and the
  exclusive `tryLock()` never succeeds: nothing gives a waiting backup precedence.
- Measured on a test VM: `backup` while a project's tasks ran ended 75 after 180 s with that message; with the
  tasks stopped it ran at once.
- On a busy machine the playbook's backup therefore never succeeds, and the conversation does not outlive the
  host.
- The host's playbook takes a backup every hour as well as at `down`
  ([HO07](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho07)), so while tasks poll no
  hourly backup succeeds either.

## Measured

- A prototype of a writer preference, against three processes taking `keep.lock` shared back to back, each for a
  random time, five runs each: with holds of 1-3 s the backup held `keep.lock` after 1.9-2.9 s, with holds of
  8-12 s after 10.0-11.8 s; today's code exited 75 every time at the bound it was given. A poll started while the
  backup waited answered 75 within 1 s. The whole unit suite stayed green with it.
- So a backup waits at most about the longest poll already running: one long sync of `--wait` (at most 30 s,
  `Poll.MAX_WAIT_SECONDS`) and its delivery, well inside `LOCK_WAIT` of 180 s, which stays.

## Plan

- A second file in the state directory, `keep.wanted` (`Keep.WANTED`). A backup takes it exclusively first, within
  `LOCK_WAIT` (another backup may hold it), and holds it to its end; only then it waits for `keep.lock`
  exclusively, within the same budget.
- A poll takes `keep.wanted` shared with `tryLock`, never a wait: refused, it answers 75 at once, as today when a
  backup holds `keep.lock`. Holding it, it takes `keep.lock` shared as today and lets `keep.wanted` go at once
  (`Keep.shared(channel, state)`, called from `Poll`).
- `copyPositions` and `restore`'s check of the positions already take only `.since` files and `refs`, so
  `keep.wanted` is never copied or restored; a test holds that.
- Red first: `KeepWriterPreferenceTest`, three `SharedLockLooper` processes holding `keep.lock` shared back to
  back, and a backup that must hold it within the longest hold plus 2 s; today it answers 75 ("writer
  starvation"). Then: a poll started while a backup waits answers 75 within 1 s; a restore refuses `keep.wanted`
  among the positions.
- **Accepted:** backups started back to back can each take `keep.wanted` at once after the last, holding polls at 75 for as long as they keep coming; the host's playbook takes one an hour, so a poll waits at most one backup.
- Files: `Keep.java`, `Poll.java`, `KeepTest.java`, the new `KeepWriterPreferenceTest.java` and
  `SharedLockLooper.java`, `CHANGELOG.md`.

## Acceptance

- An integration or unit test with several processes polling back to back (overlapping shared holds) where
  `backup` gets the lock and completes within a bound; seen to fail without the fix (75 after the wait).
- Polls started while the backup waits or runs answer 75 at once, and the next poll after the backup hands over
  every message without loss.
- The existing test that a backup holding the lock makes a poll answer 75 at once stays green.
- On a test VM, `backup` while a project's tasks poll ends 0 within one long sync, read with the tasks running.

## To be checked

- On a test VM with a project's tasks running: the backup's wait, measured.
