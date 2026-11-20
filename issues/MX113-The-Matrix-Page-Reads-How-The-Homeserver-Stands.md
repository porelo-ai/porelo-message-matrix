# MX113 — The Matrix Page Reads How The Homeserver Stands

```json
{
  "$schema": "issue-1.json",
  "id": "MX113",
  "title": "The Matrix Page Reads How The Homeserver Stands",
  "state": "Analyzed",
  "size": "M",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "A read-only verb `overview` answers in one JSON object how a machine's homeserver stands - installed, running, who can reach it - and for each project room Porelo made: the agents present, and for one person what is unread and what is meant for them"
}
```

**Why this size.** One new verb over code that exists (`Members`, `MeantFor`, the unit's state), a contract section, unit
tests and one integration test against the real homeserver; no other verb changes.

**What must be true.** The window's Matrix page, under this computer or a host, shows the homeserver's state, its
project rooms, the agents present in each, and what waits for the person, from one call that changes nothing: no
homeserver started, no account made, no position moved, no receipt sent.

## Why

Today Porelo can ask one project's `members`, and `receipt` for one message it sent. The page needs every project
room of the machine at once and what the person has not read, which no verb answers. The transport still decides
nothing: which of Porelo's messages is a question stays Porelo's, read from what it sent and `receipt`.

## Plan

- **The verb** `overview [--person N | --login L]`, in the `Lifecycle` group's dispatch in `Main`, with the secrets
  on stdin as the lifecycle verbs take them; on a host's gate as the homeserver's account with the administrator's
  token, as `keep-room` takes it. Exit codes as `members`: 75 not reached, 77 token refused, 78 configuration.
- **The answer:**
  `{"state": "running"|"stopped"|"not installed", "reaches": {"address", "server", "gate": bool, "federation": false},
  "rooms": [{"project", "alias", "room", "agents": [{"task", "shown", "state"}], "person": "join"|"invite"|"none",
  "unread": n, "meant": n}]}`.
  - `state` from the user unit without starting it (`Unit`/`PodmanUnit`: unit file absent is "not installed", inactive
    is "stopped"); not running answers no `rooms` and exit 0, so the page shows the state.
  - `reaches` from `homeserver.conf` and the gate's files; federation is never on, said so the page need not guess.
  - `rooms`: the administrator's joined rooms whose canonical alias is `#porelo-<P>:<server>` and which pass the
    room check `members` already makes (made by the administrator, invite-only, guests forbidden); `agents` are the
    `task` members `Members.answer` gives.
  - `unread` and `meant`, only with `--person`/`--login`: the text lines (`m.room.message`, `m.text`, no edits) after
    that person's own read receipt in the room, not their own; `meant` those of them that name the person by
    `MeantFor`'s rule, mention them, or say `All agents`. Read as the administrator from the room's timeline and the
    receipts its sync carries, paged back to the receipt, at most 1000 lines per room ("1000+" beyond).
- **Red first:** a unit test of the answer's shape from recorded events (`OverviewTest`), then an integration test
  against the homeserver image with a task, a person and three lines, one read, one naming the person.
- **Contract:** a section "Reading how the homeserver stands" in `doc/transport.md`, with `describe` naming the verb;
  changelog under Added.

## Acceptance

- With the homeserver stopped, `overview` answers `"state": "stopped"` and starts nothing (the unit's state before
  and after is the same); without the package, `"not installed"`.
- With two projects, a task each and a person who read one of three lines, one of the unread naming them, the answer
  lists both rooms, each task once, `unread` 2 and `meant` 1; a run of `poll` or `room read` after it hands over the
  same as before.
- A homeserver whose administrator token is refused answers 77 and names no token.

- **The page's numbers:** its unread tile counts `meant`, what is meant for the person; its room list shows each
  room's `unread`, every unread line.
- **Answer opens the question in the person's own Matrix client**, as [MX114](README.md#mx114) gives the address; the
  window answers nothing itself and keeps no Matrix token, so `overview` never needs the person's.

## To be checked

- Whether Tuwunel hands the administrator other members' `m.read` receipts in an initial sync, with a filter on the
  project rooms; Matrix has no endpoint that reads another member's receipt, so without it `unread` cannot be read as
  the administrator, and the size grows.
- With Agent Core: the verb's call from `porelod` under each machine, how often the page asks, and that the
  "questions agents ask" tile is Porelo's own list from its sends and `receipt`, with a batch form of `receipt` if one
  process per question is too slow (measured with 50 questions).
- Direct chats between a task and the person: the administrator is in none. Whether the page counts them (Porelo
  knows each one it sent) or the transport reads them with the tasks' tokens.
