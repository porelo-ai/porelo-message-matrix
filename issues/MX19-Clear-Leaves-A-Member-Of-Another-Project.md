# MX19 — Clear Leaves A Member Of Another Project

**Status:** Analyzed.
**Size:** S - one loop in `Lifecycle.clear` and the sentence in `doc/transport.md` that says what it deactivates;
the answer's shape (`removed`, `kept`) stays, and an integration test with the real homeserver.

**What must be true.** Clearing one project's conversation never cuts a person off from another project on the
same homeserver: a member who is also in another project's room stays active there, or `clear` says beforehand
whom it would cut off and does not do it unasked.

## Why

`clear --project P` deactivates every account in the project's room - its tasks', its relay's and its people's -
as `doc/transport.md` says. A person's account is `@<name>` on the homeserver, not one per project, so a person
who is in projects A and B is deactivated by `clear --project A` and loses B too.

## Plan

- **Read in today's code:** `Lifecycle.clear` (`Lifecycle.java:325`) walks the room's members and calls
  `AdminRoom.deactivate` (`AdminRoom.java:180`) on every joined or invited account of the server but `@porelo`,
  then deletes the room. Nothing asks whether a member is in another room. The relay is `@porelo-relay-<P>`, one
  per project, so it is never in two; a person (`join`, `@<N>`) can be; a task (`enroll`, `@<T>`) only if Porelo
  gives two projects' tasks the same name, which is asked under *To be checked*.
- **Steps:**
  1. Before the loop, the other Porelo rooms: `@porelo`'s `/joined_rooms`, less this one, each taken only where
     `ours(...)` holds, as `clear` already judges its own room. Their members are read through
     `/rooms/<id>/members` - the client API the code uses today, no admin command whose text could change.
  2. A member joined or invited in one of them is not deactivated. Under the answer to the open question, it is
     either said in `kept` (`what`: the account, `why`: the other project's room alias) while the rest goes, or
     `clear` stops before anything goes and names every such account.
  3. `doc/transport.md`'s row for `clear --project P` says "every account in the project's room that is in no
     other project's room", and what happens to the others.
- **The test that goes red first:** `LifecycleIT`, with the real homeserver: `setup` A and B, `join --person ann`
  into both, `clear --project A`. Today `ann` is deactivated: her login fails and B's room loses her. After the
  change she logs in, B's room still lists her as joined, and `kept` names her with B's alias. A second case: a
  person only in A is still deactivated, so the check does not keep everybody.

## Acceptance

- `LifecycleIT` as above, red on today's code with `ann`'s login refused, green after.
- The person only in A is deactivated and named in `removed`; the guard is seen to fail when the check is
  broken on purpose so that it keeps every member.
- `clear --project A` run twice still exits 0 and the second run finds nothing.

## Open questions

- **A member in another project:** (a) `clear` goes on, leaves that account active, and names it in `kept` with
  the other project's room; or (b) `clear` changes nothing and refuses, naming each such account, until they
  are taken out of the other project first. I recommend (a): `clear` already keeps what it must not take and says
  why, Porelo reads the answer without parsing it, and (b) would leave a project that cannot be cleared while a
  person is in two.

## To be checked

- With Agent Core: whether two projects' tasks can be enrolled under the same `--task` name, so that a task's
  account can be in two rooms; if they can, the same check covers it.
- Whether `deleteRoom` of A takes a kept person out of A's room on its own, read on the pinned Tuwunel in the
  integration test, so nothing of A stays in that person's room list.
