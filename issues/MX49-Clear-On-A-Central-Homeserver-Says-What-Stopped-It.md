# MX49 — Clear On A Central Homeserver Says What Stopped It

**Status:** Analyzed.
**Size:** S - `Lifecycle.machineId` and `clearCentral` with their tests; the exit codes are the existing contract's.

**What must be true.** A host given back after its machine was dismissed, or while the homeserver cannot be reached,
learns which of the two it is. A machine the homeserver no longer knows counts as already cleared.

## Plan

Seen in sprint 6i (28 Oct): `down` stopped at the host's vault clear, because `clear` answered "the machine's token
Porelo holds is not valid on https://matrix.porelo.internal:8009: … not reachable". `Lifecycle.machineId` turns every
failure of `whoami` into exit 78 with "not valid". Here the homeserver was simply off the VPN (6i-M5.3 before the
give-back).

- `machineId`: a homeserver that cannot be reached is **75** (temporary), saying so; only a token the homeserver
  refuses (`401 M_UNKNOWN_TOKEN`) is the case below.
- `clearCentral`: a refused token, or a machine banned from the room by `dismiss`, means the homeserver has let this
  machine go. `clear` then answers **0** and says so under `kept` ("the homeserver had let this machine go already").
  It forgets its poll position as today.
- Red first: `CentralIT` runs `clear` after `dismiss` (today 78, expected 0) and with the homeserver stopped (today 78,
  expected 75).

## Acceptance

- After `dismiss`, a host's `clear` exits 0 and names what it found.
- With the homeserver stopped, it exits 75 and says the homeserver was not reached.
- Related: porelo [B318](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b318) (`vault clear
  --json`) and porelo-host [HO50](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho50) (`down`
  gives back when only a transport could not clear) handle the unreachable case on their side.
