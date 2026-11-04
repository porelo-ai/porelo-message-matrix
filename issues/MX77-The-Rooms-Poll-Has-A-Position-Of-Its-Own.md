# MX77 — The Room's Poll Has A Position Of Its Own

**Status:** Analyzed.
**Size:** M - `Poll` and its callers in `Lifecycle`, one key more in the transport's contract, docs, a decision and an
integration test; nothing in Porelo.

**What must be true.** `poll --persons` reads one project's room and no other room of the account it reads as, and
where it stopped belongs to that room: a `--direct` poll of the same account moves nothing of it, and when the reading
account changes no message is repeated or lost.

## Why

- A poll's position and lock are named by homeserver and account (`Poll.key`), and `poll --persons` handles every room
  in the sync's `rooms.join`.
- **Today that does no harm, read in the code, not run:** Porelo polls the room with the relay's token and each task's
  direct chats with that task's token, so the keys differ; the relay answers no invitation, so it sits in its project's
  room and at most in one it was left in by a room made anew.
- **It does harm the moment a task reads the room,** as a room without helpers needs: a task's `--direct` poll would move
  the room poll's position past messages not yet handed over, a person's direct chat with that task would become a room
  person's file without `direct`, and a change of reader would change the key, so the room is read again from its start.
- So this comes first and changes nothing a person sees.

## Plan

- **The room reaches `poll` without a change in Porelo:** `Lifecycle` adds `PORELO_MATRIX_ROOM`, the room's id, to the
  `secrets` of `setup`'s answer, on the account's own homeserver and a central one. Porelo keeps that map key for key
  and hands it on `poll`'s input. `enroll`'s map stays without it. It is no secret; the map holds such keys already.
- **`Poll`, with `--persons` and that key:** the sync filter's `room.rooms` holds that one room, and `once` also skips any
  other room a homeserver answers. `Poll.key(config, "room\n" + room)` names the `.since` and the `.lock`; no account id
  holds a line break, so it never meets an account's key. The rooms saved beside the position are that room alone, so
  `takenOnlyInRoomsOutside` still answers 78 when the reader is out of it.
- **Without the key,** as with a vault from before and until its next `setup`, `poll --persons` goes as it does today.
  `--direct` and a poll with neither option do not change.
- **A relay's position moves once:** with no room position yet and a relay's `.since` there, the room poll takes it over
  by renaming, holding the old lock as well, so nothing is read again.
- **`Lifecycle.forgetPosition`'s three callers** (`setup` for rooms left, `clear --project`, `clear`) forget the room's
  position as well as the account's. `Keep` copies every `.since` of the directory, so a backup carries it unchanged.
- **Docs:** `doc/transport.md` (the key, `poll`), `doc/decisions.md` (*The position of a poll is saved after its files*
  says one file per homeserver and account).
- **Red first:** a `PollTest` with the key set and a sync answering two rooms: only that room's message is handed over,
  and the position lies under the room's key.

## Acceptance

- `PollIT`, on the real homeserver, with one account: a room message, then `poll --direct`, then `poll --persons`: the
  message is handed over once. In the other order too, and with both running at once.
- `PollIT`, with two accounts in the room: account one polls, a message is sent, account two polls with the first
  one's position: only the new message is handed over.
- `PollIT`: a person's direct chat with the reading account and a second room it is in: neither reaches `--persons`.
- `PollIT`: more messages than one sync's page behind the position, with the reader changed: none lost.
- `KeepTest`: a backup and restore carries the room's position. `clear --project` leaves none behind.
- An existing relay's position: the first poll after an upgrade hands over nothing twice.
- Seen to fail: with the room's key back to the account's, the first and second tests fail; with the filter and the skip
  gone, the third fails.

## To be checked

- That the pinned Tuwunel takes a position from another account while a `room.rooms` filter is set. Measured so far with
  an application service and without that filter; a position is opaque by the Matrix specification, so this holds for
  the pinned server and is held by the test above.
- What a room made anew leaves behind: `setup` answers a new id, so the old room's position stays until `clear`, which no
  longer knows that id. Whether `setup` can name it from the key it replaces, or whether it may lie there.
- That no Porelo path reads `setup`'s map as secrets only to mask or print them, so the new key changes nothing there.
