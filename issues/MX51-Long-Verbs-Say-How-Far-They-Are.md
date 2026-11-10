# MX51 — Long Verbs Say How Far They Are

```json
{
  "$schema": "issue-1.json",
  "id": "MX51",
  "title": "Long Verbs Say How Far They Are",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 14,
  "blockedBy": [],
  "covers": "`setup`, `backup` and `restore` say on stderr what they wait for and for how long, every 10 s; waits for porelo to show a transport's stderr as it comes"
}
```

**Why this size.** stderr lines in `LocalHomeserver.awaitUp` and `Keep`, with their tests; stdout's contract is
unchanged.

**What must be true.** A person who runs a command that waits on the homeserver sees what it waits for and how long
it has waited. A silent minute no longer looks like a hang.

## Plan

Long CLI processes do not run without a status. The lines reach a person only once porelo shows a transport's
stderr while the verb runs; until then only when the transport runs directly. The waits are `LocalHomeserver.awaitUp` (up to `START_TIMEOUT`, 180 s, after a start or
restart: `setup`, and `setup` after a restore) and `Keep` (`backup`: the lock, the snapshot, copying; `restore`: the
one-off run; `setup` after a restore: stopping, the one-off run, starting).

- Each writes one stderr line when a phase starts, then one every 10 s while it waits: `waiting for the homeserver on 127.0.0.1:8008 (20 s of 180)`. The last line before a timeout says what was waited for. Lines hold no secret: they
  go through the same filter as the homeserver's last-error line.
- stdout stays the one JSON answer. porelo shows a transport's stderr on a failure only; whether it shows these lines
  as they come is porelo's (To be checked).
- One package-private `Progress` writes the lines for both: a phase line at once, then one every 10 s from a daemon
  timer thread, so a blocking copy or a one-off run is reported too; closing it stops the timer before the verb's own
  answer or failure. Elapsed time is read from the timer's own monotonic clock, so a tick after 10 s always sees
  10 s; the clock, the interval and the start's polling pause are injectable, so no test waits 10 s.
- Every verb hands its own stderr down, `expel` and `keep-room` too, which start the homeserver as well; no line
  goes to `System.err` past it. Both start paths filter with the same secrets. An interrupted command still prints
  a plain phase line; only the log's last error is withheld on an interrupt, as before.
- The locks and the second snapshot are announced with the time left to their deadline, rounded up to a second;
  the line at a timeout is said once and never past the limit.
- Red first: `LocalHomeserverTest` with a stand-in clock and a homeserver that answers after 25 s expects the phase
  line and two progress lines on stderr.

## Acceptance

- A `setup` waiting on a slow start prints the phase and a line every 10 s, and stdout is unchanged.
- `backup` and `restore` name each phase as it starts.
