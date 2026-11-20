# MX114 — A Room Opens In The Person's Matrix Client

```json
{
  "$schema": "issue-1.json",
  "id": "MX114",
  "title": "A Room Opens In The Person's Matrix Client",
  "state": "Analyzed",
  "size": "S",
  "priority": "High",
  "rank": 2,
  "blockedBy": ["MX113"],
  "covers": "Each room `overview` lists carries the addresses a client opens it by, a room's own and a message's, and the contract says how Element Desktop opens them on this computer and through a host's gate, measured"
}
```

**Why this size.** Fields added to one answer and a contract section, with one measured run of Element Desktop against
the local homeserver and a host's gate; the window starts the client, as it does today for signing in.

**What must be true.** From the Matrix page, Open on a room and Answer on a question bring the person into that room,
at that message, in their own Matrix client, signed in on that machine's homeserver; nothing else of the client changes.

## Plan

- **In `overview`'s answer**, each room gains `open`: `"#porelo-<P>:<server>"`, the alias a client resolves through
  `GET /_matrix/client/v3/directory/room/`, which the gate already passes; and `matrix`: the standard `matrix:` URI
  (`matrix:r/porelo-<P>:<server>`), for a client registered for it. A message's address is the room's id and its event
  id, which `send` already prints as `reference`; the contract states how the two join into
  `matrix:roomid/<id>/e/<event>`.
- **The window's part, stated in the contract for the frontend:** Element Desktop opens
  `element://vector/webapp/#/room/<alias or id>[/<event>]` in the instance it already starts with the machine's
  homeserver (`--profile` per host); a person who is only invited sees the invitation there and accepts it.
- **Red first:** `OverviewTest` expects `open` and `matrix` per room; the measured run follows the code.
- **Contract:** "Opening a room in a client" in `doc/transport.md`; changelog under Added.

## Acceptance

- Element Desktop on Ubuntu, signed in as a person on this computer's homeserver, opens a project room by its alias
  and a message by its room and event id; the same through a host's gate forward. What was seen is written in the
  hand-over, a screenshot each.
- A room the person is not in shows Element's "not found or no access", never another room.

## To be checked

- Whether Element Desktop takes the `element://vector/webapp/#/room/…` address into an instance started with
  `--profile`, or needs the profile again on the command line.
- Which other client on Ubuntu (nheko) opens `matrix:` URIs through `xdg-open`, for a person without Element.
