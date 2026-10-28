# MX51 — Long Verbs Say How Far They Are

**Status:** Estimated by the operator on 2026-10-28.
**Size:** S - stderr lines in `LocalHomeserver.awaitUp`, `Keep` and `Publish`, with their tests; stdout's contract is
unchanged.

**What must be true.** A person who runs a command that waits on the homeserver sees what it waits for and how long
it has waited. A silent minute no longer looks like a hang.

## Plan

The operator's word in sprint 6i (28 Oct), after M0.2 hung silently: long CLI processes should not run without a
status. The waits are `LocalHomeserver.awaitUp` (up to `START_TIMEOUT`, 180 s, after a start or restart: `setup`,
`publish`, `restore`), `Keep` (`backup`/`restore`: stopping, copying, starting) and `Publish` (the certificate, then
the restart).

- Each writes one stderr line when a phase starts, then one every 10 s while it waits: `waiting for the homeserver on
  127.0.0.1:8009 (20 s of 180)`. The last line before a timeout says what was waited for. Lines hold no secret: they
  go through the same filter as the homeserver's last-error line.
- stdout stays the one JSON answer. porelo shows a transport's stderr on a failure only; whether it shows these lines
  as they come is porelo's (To be checked).
- Red first: `LocalHomeserverTest` with a stand-in clock and a homeserver that answers after 25 s expects the phase
  line and two progress lines on stderr.

## Acceptance

- A `setup` waiting on a slow start prints the phase and a line every 10 s, and stdout is unchanged.
- `backup` and `restore` name each phase as it starts.

## To be checked

- Whether porelo passes a transport's stderr through while the verb runs (`porelo talk join`, the task start's
  *Joining the project's conversation*), or only on failure. Ask Agent Core.
