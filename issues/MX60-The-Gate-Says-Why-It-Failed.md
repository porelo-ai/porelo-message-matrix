# MX60 — The Gate Says Why It Failed

```json
{
  "$schema": "issue-1.json",
  "id": "MX60",
  "title": "The Gate Says Why It Failed",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 4,
  "blockedBy": [],
  "covers": "The host's gate logs why it answered 502 or could not act as the administrator (token missing or refused, homeserver silent, an unexpected status of the administrator's API), never a token"
}
```

**Why this size.** `Gate.java`'s refusal line and the causes it names, with `GateTest` cases; nothing another repository reads.
**How it stands.** Analyzed and built inside the Porelo task of this repository's trial move.

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
