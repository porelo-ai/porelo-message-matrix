# MX100 — A Task Answers Only Where It Still Is

```json
{
  "$schema": "issue-1.json",
  "id": "MX100",
  "title": "A Task Answers Only Where It Still Is",
  "state": "Implementing",
  "size": "S",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "A task never answers into a direct chat it has left or was taken out of, and a chat it cannot read does not stop its answer"
}
```

**Why this size.** which of a task's direct chats `send --to <person>` may write into, and its tests.

**How it stands.** Implementing (2026-11-10), after [MX102](README.md#mx102) on the coordinator's word (16:39Z).

**What must be true.** A task's answer to a person never goes into a direct chat the task itself has left or was taken
out of, and one chat whose state the task may not read does not stop the answer from reaching another.

## Why

The code reading of [MX98](README.md#mx98) found two ways `Direct.chatWith` still chooses wrong, both there before
[MX98](README.md#mx98) and left out of it, since its plan changes only which open chat is chosen:

- It checks only the person's membership. A person who took the task out of a chat stays joined there, the chat counts
  as open, and the answer fails with 77 instead of going into another chat or a new one.
- It reads the person's membership with `find`, which turns a 403 into 77, so one chat whose state the task may not
  read stops the send before the other chats are looked at.

## Plan

- **Only chats the task is joined in count**: `joined_rooms` of the task's account, read once per send, intersected
  with `m.direct`; a 403 or 404 on a chat's member state skips that chat.
- **The test that goes red first:** `SendTest` against a scripted homeserver: of two open chats the task left the
  newer, the answer goes into the older; a 403 on the older's member state leaves the newer chosen.
