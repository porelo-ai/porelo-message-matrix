# MX70 — The Gate's Rules Are Held To Their Edges

```json
{
  "$schema": "issue-1.json",
  "id": "MX70",
  "title": "The Gate's Rules Are Held To Their Edges",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 27,
  "blockedBy": [],
  "covers": "Moving an edge of who belongs to a login, or of which redirect is the person's own client, fails a unit test"
}
```

**Why this size.** table cases over plain functions in one test class.

**What must be true.** `GateRules` decides who belongs to a login and which redirect is the person's own client; moving any of its edges fails a unit test.

## Plan

`GateRulesTest`, as a table: `localpart` and `belongs` for `@a:s`, `@:s`, `@a:`, a space or `@` in the server part, an invalid login, a name of 160 and of 161 characters, `.alice`; a redirect that is no URI, and each of the other refusals.

## Acceptance

- Measured: PIT on `GateRules` leaves only mutants the issue names as equivalent.
- Seen to fail: each case against its mutant.

## Measured

PIT on `GateRules`: 96 killed, 4 left, each equivalent. Line 29 and line 51: an account in that form fails the task name
next, so no owner comes of it. Line 94, both edges: `new URI` refuses a raw space and a raw DEL as well.
