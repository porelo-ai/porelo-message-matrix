# MX69 — A Lost Message Or Copy Fails A Test

**Status:** Backlog.
**Size:** M - cases in four test classes, some against fakes that must first record more; no production code unless a test finds a defect.

**What must be true.** Whoever breaks what keeps a message or a copy whole sees a unit test fail: a message silently dropped, a gap in a sync not filled, a sender not recorded, a restore that holds nothing or the wrong homeserver's data.

## Plan

- `SendTest`: `transactionId` differs for another message and for another signature, is the same for the same pair, and the length prefix keeps `ab`+`c` apart from `a`+`bc`. A direct message still fits its limit.
- `PollTest`: a `limited` timeline with a `prev_batch` fetches what it missed, in order; `.sender` is written with the homeserver's sender; `.sig` is absent for a message that came with none; `deliver` answers true; the reference is remembered before the message appears.
- `KeepTest`: a backup copies the database, and the copy holds it; a restore refuses a copy with anything but files and directories; a copy of another homeserver's server name is refused; what a failed restore left is named; the rotation of the administrator's password stops the unit and starts it again even when the run fails.
- `DirectTest`: an encrypted invitation is left with its reason, when the encryption event is not the last in the state.

Red first as in the gate's refusals: each test seen to fail against its mutant.

## Acceptance

- Measured: PIT kills the mutants of `Send.transactionId`, `Poll.deliver`, the gap fetch, `Keep.backup`'s copy, `Keep.same` and the restore's checks, or the issue names why not.
- Seen to fail: each new case.
- A real defect found stops here: the fix follows the failing test.

## To be checked

- Which of these `KeepIT`, `PollIT`, `SendIT` and `DirectIT` already hold. An IT holds them only where the build runs it; a unit test is still wanted where it is cheap.
