# MX52 — A Person Joins Another Project's Room Unchanged

**Status:** Backlog, the operator's word on 2026-10-28.
**Size:** S - one branch of `Lifecycle.join`, its answer and the doc's table, with an integration test; the answer
gains one shape Porelo and porelo-frontend show.

**What must be true.** A person who already has an account on this homeserver for one project joins another project's
room on it without a new password. They accept an invitation in their own client, and every session and the room they
were in stay as they were.

## Plan

Seen in an operator's walk: `porelo talk join sluice-dry carol` answered `'carol' has an account already;
--reset gives it a new password` (77). `carol` was in another project's room. `Lifecycle.join` takes an existing account
without `--reset` only when `part` took it out of this room and it is in no other project's room.

- In `join`, an existing account that is a member of another project's room on this homeserver (`membersElsewhere`)
  and not of this one: the administrator invites it into this project's room (`invite`, as `admit` invites before a
  join). No password is set and no session is touched.
- The answer for that case is `{"login":{"homeserver":…,"user":"@N:server","room":"#porelo-P:server"},"invited":true}`
  with no password, and `shown` says to accept the invitation in the person's client. With `--token`, it stays refused
  (77): a token needs a login, which needs the password.
- An account in no project's room, or one that left by itself, is refused as today; `--reset` behaves as today.
- Red first: `LocalHomeserverIT` joins a person to project A, then to project B without `--reset`. Today that is 77;
  expected 0, `invited`, the person invited into B's room and still a member of A's, and their login still valid.

## Acceptance

- The person's existing access token answers 200 after the second join, and their membership in B's room is `invite`.
- A second join of the same person to B answers the same and invites nobody twice.

## To be checked

- How Porelo's `talk join` and porelo-frontend's *Join* show an answer without a password: ask Agent Core and Agent
  Frontend before building.
