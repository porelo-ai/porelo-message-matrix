# MX106 — The Busy Room Knows The Project's Other Persons

```json
{
  "$schema": "issue-1.json",
  "id": "MX106",
  "title": "The Busy Room Knows The Project's Other Persons",
  "state": "Review",
  "size": "XS",
  "priority": "High",
  "rank": 3,
  "blockedBy": [],
  "covers": "The busy-room step names the project's other active persons, as the host answers them, to the rehearsal, so their accounts and helpers in the room are no stranger's"
}
```

**Why this size.** One option of the rehearsal, a further argument of its script, the step's read of the host's
answer, and their tests.
**How it stands.** Review (2026-11-11): built, the rehearsal's and the script's tests green, the two new tests seen red
with the fix removed. The step is a test script; its proof is the next VM run. Found by Agent Machines' VM proof
on 0.5.1~snapshot.486 (09:43Z): "the room reads in order, with no stranger" counted the operator's own account and its
`@porelo.<login>` as strangers, though the host has that person active in the project.

**What must be true.** A busy-room run on a host where more than one person is active in the project fails the
scenario of the room's order only for a member or a line that is no task, no person the host has active in the
project, and no helper of such a person's login.

## Plan as built

- **`RoomRehearsal --person-login <login>`**, with `--project` only: that login's account on the signed-in person's
  server, known only after the sign-in, is a further person of the room, and that login's helpers are none of a
  stranger. A value that is no login, or one given twice, is refused (64), and so is the option beside `--room`.
- **`build/rehearse-busy-room.sh <login> <host> <project> [<person>...]`** passes each further login as
  `--person-login`, each checked as a login before anything runs.
- **`build/rehearse-steps/busy-room`** reads `<report>/org-status.json`, the answer the run's project step got as root
  from `porelo-host org status --json`, and passes every person in state `active` with the project among their
  projects, the run's person left out. Without that answer it stops (2), since it cannot tell a person from a stranger.
- **Tests:** `RoomRehearsalTest.aProjectsOtherPersonsAreNamedByTheirLogins` and
  `RehearseBusyRoomTest.theProjectsOtherPersonsReachTheRehearsal`, plus the script's refused calls.
