# MX70 — The Gate's Rules Are Held To Their Edges

**Status:** Implementing.
**Size:** S - table cases over plain functions in one test class.

**What must be true.** `GateRules` decides who belongs to a login and which redirect is the person's own client; moving any of its edges fails a unit test.

## Plan

`GateRulesTest`, as a table: `localpart` and `belongs` for `@a:s`, `@:s`, `@a:`, a space or `@` in the server part, an invalid login, a name of 160 and of 161 characters, `.alice`; a redirect that is no URI, and each of the other refusals.

## Acceptance

- Measured: PIT on `GateRules` leaves only mutants the issue names as equivalent.
- Seen to fail: each case against its mutant.
