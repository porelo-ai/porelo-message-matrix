# MX52 — A Person Joins Another Project's Room Unchanged

**Status:** Review.
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
  and not of this one: the administrator invites it into this project's room (`invite`, as `joinRoom` does before a join). No password is set and no session is touched.
- The answer for that case is `{"login":{"homeserver":…,"user":"@N:server","room":"#porelo-P:server"},"invited":true}`
  with no password, and `shown` says to accept the invitation in the person's client. With `--token`, it stays refused
  (77): a token needs a login, which needs the password.
- An account in no project's room, or one that left by itself, is refused as today; `--reset` behaves as today. One
  `part` took out of this room while another project keeps it is invited back the same way.
- A name that is another project's task, not a person, is invited the same way, since the transport tells the two
  apart only by their rooms; the task's poll declines an invitation to a room that is no direct chat, and `part`
  takes the invitation back.
- The shape is agreed with porelo, whose daemon passes `invited` on to the window, and porelo-frontend, whose window
  shows a login's fields only where present and words its steps for an invitation by `invited`.
- Red first: `LifecycleIT` joins a person to project A, then to project B without `--reset`. Today that is 77;
  expected 0, `invited`, the person invited into B's room and still a member of A's, and their login still valid.

## Acceptance

- The person's existing access token answers 200 after the second join, and their membership in B's room is `invite`.
- A second join of the same person to B answers the same and invites nobody twice.
