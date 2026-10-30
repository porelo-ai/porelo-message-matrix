# MX55 — A Person Has Their Own Account In The Room

**Status:** Accepted.

**What must be true.** A person named in the project's access file reads and writes in the project's room on a host's
homeserver from their own Matrix client, under an account of their own, reached over an ssh forward to the host's
loopback; a person the access file drops is out of the room at the next `members`, as a dropped machine is.

## Why

On a central homeserver `join` is refused (77): people manage their own accounts there. On a host's homeserver that
nobody can do. `machine-token issue` hands out a registration token per person's machine, for its Porelo to register
`@porelo.<machine>`, and the room is made and kept by the project admin's `@porelo.<machine>` (`setup --create-room`,
`members`), whose token only that Porelo holds. `members` invites only the listed machines' `@porelo.<Mi>`, so nobody
can invite a person into the room. In normal work a person reaches a host only by ssh, so the client reaches the
homeserver through a forward of its loopback port, as the tasks' transport on a laptop does.

## Acceptance

- A person registers an account on the host's homeserver, signs in from a Matrix client through an ssh forward to
  `127.0.0.1`, is invited by a verb run as the room's maker, joins, and reads and writes in the room.
- A verb run as the room's maker takes that person out of the room again.
- `members`, given the people of the access file beside its machines, invites each person listed and not yet in, and
  takes out every person the list no longer names; run again with the same lists it changes nothing.
- A person's account whose name ends as a machine's, or is the administrator's, a relay's or a task's, is refused as
  a person, and `members` never takes a machine's account out as a person's or a person's as a machine's.
- Seen to fail: a person dropped from the list stays in the room; a person's name that ends as a listed machine's is
  invited.

## To be checked

- How a person registers on the host's homeserver: a registration token of their own from the host administrator's
  helper, or an account the room's maker or the host's helper makes with a password shown once.
- The contract proposed with Agent Core, to be read against the code: a person is a developer of the access file,
  their Matrix login their host account's name; Porelo runs `members --project P --machines … --persons A,B,…`, sorted,
  only when `describe` lists `persons` among what it takes; the answer adds `persons_invited` and `persons_removed`
  beside `invited` and `removed`. Without `--persons`, `members` leaves every person as it is. Porelo calls no
  invite or remove verb of its own.
- That the ssh forward reaches the room for a client as it does for the transport, measured on a VM.
