# MX69 — A Lost Message Or Copy Fails A Test

```json
{
  "$schema": "issue-1.json",
  "id": "MX69",
  "title": "A Lost Message Or Copy Fails A Test",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 26,
  "blockedBy": [],
  "covers": "A silently dropped message, an unfilled sync gap, an unrecorded sender or a hollow restore fails a unit test"
}
```

**Why this size.** cases in four test classes, some against fakes that must first record more; no production code unless a test finds a defect.

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

## Measured

PIT on the VM, unit tests only. Killed: `Send.transactionId`'s length, message and signature, `Poll.deliver`, the gap
fetch, `Keep.backup`'s copy, `Keep.same`, and the restore's checks. PIT showed the message was no part of the id's test
at first: another message of another length passed for it.

Left, and why they stay:

- `Poll` lines 184 to 198, 312, 333, 346: the wait for news and the count of what was handed; they decide how long a
  poll waits, never what is delivered. `Poll` lines 81 to 85, 257: argument forms and `--direct`, held by `PollIT` and
  `DirectIT`, as PIT runs no integration test. `Poll` line 523: a lock's return, released with its channel.
- `Keep` lines 462, 479, 480: the pause while waiting for a lock. Line 474: as line 523. Line 570: one of two conditions
  of a name; the other was seen red by hand.
- `Send` lines 240, 272, 282: the exact byte at the event's limit, where the fit cuts one byte more or fewer. Lines 81,
  87, 88, 146: argument forms held by `wrongArguments` at their other edges.
- `Direct` line 64: one attempt more or fewer at writing `m.direct`.

`KeepIT`, `PollIT`, `SendIT` and `DirectIT` hold the real homeserver's side; these tests hold what a build without them
would miss.

**No defect found.**
