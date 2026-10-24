# MX36 — A Backup Runs While Tasks Poll

**Status:** Idea.

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

## Acceptance

- An integration or unit test with several processes polling back to back (overlapping shared holds) where
  `backup` gets the lock and completes within a bound; seen to fail without the fix (75 after the wait).
- Polls started while the backup waits or runs answer 75 at once, and the next poll after the backup hands over
  every message without loss.
- The existing test that a backup holding the lock makes a poll answer 75 at once stays green.

## To be checked

- Whether a second lock file that a backup takes first, and that new polls test and refuse on with 75 (writer
  preference), lets the running polls drain within one long sync.
- How long the backup then waits at most (one `--wait` of 30 s plus margin), measured, and whether `LOCK_WAIT`
  of 180 s still fits.
- The same measured on the test VM with a project's tasks running.
