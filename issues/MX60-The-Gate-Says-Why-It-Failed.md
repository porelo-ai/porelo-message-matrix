# MX60 — The Gate Says Why It Failed

**Status:** Idea, 2026-10-30.

**What must be true.** When the host's gate answers 502, or refuses a sign-in or a registration because it cannot act
as the administrator, its log line on the host names the cause: the administrator's token is missing, unreadable, or
refused by `whoami`; the homeserver's socket does not answer; or the administrator's API answered a status the gate
did not expect, with that status. A host admin then finds the fault in the gate's journal without reading its code.
The line never holds a token, a password or a body.

## Why

Seen on the sprint 6m test host on 2026-10-30: the SSO redirect answered 502 and `POST /register` 403, and the gate's
journal said only that the request was refused. Telling a missing administrator token from a homeserver that is not
up took reading `Gate.java`.

## Acceptance

- `GateTest` cases for each cause above see the log line name it, and see no token in it.
- Seen to fail first: today's line names only method, path and status.
