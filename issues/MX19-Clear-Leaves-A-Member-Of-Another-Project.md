# MX19 — Clear Leaves A Member Of Another Project

**Status:** Accepted; found in the code reading ([MX16](README.md#mx16)).

**What must be true.** Clearing one project's conversation never cuts a person off from another project on the
same homeserver: a member who is also in another project's room stays active there, or `clear` says beforehand
whom it would cut off and does not do it unasked.

## Why

`clear --project P` deactivates every account in the project's room - its tasks', its relay's and its people's -
as `doc/transport.md` says. A person's account is `@<name>` on the homeserver, not one per project, so a person
who is in projects A and B is deactivated by `clear --project A` and loses B too.

## To be checked

- Whether a person's account stays and only leaves the project's room, or `clear` refuses and names the people
  who are in another project, and what `removed` and `kept` say in each case.
- Whether a deactivated account can be brought back at all, by `join --reset` or Tuwunel's admin commands.
- Whether tasks' and the relay's accounts can be in two projects at all, or only people's.
- That this changes `clear`'s documented answer, which Porelo reads.
