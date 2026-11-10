# MX72 — A Token Is Random And An Admin Command Takes One Argument

```json
{
  "$schema": "issue-1.json",
  "id": "MX72",
  "title": "A Token Is Random And An Admin Command Takes One Argument",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 29,
  "blockedBy": [],
  "covers": "A homeserver token not random, an admin command's user id unchecked or a credential over its limit fails a unit test"
}
```

**Why this size.** a few cases in three test classes.

**What must be true.** A homeserver token that stopped being random, an admin room command that took a user id unchecked, and a credential over its limit each fail a unit test.

## Plan

- `HomeserverTokenTest`: two tokens made in two directories differ, and neither is the encoding of zero bytes.
- `AdminRoomTest`: `deactivate` refuses a user id with a space or a line break and sends no command.
- `RoomTest`: a credential of exactly the limit passes; one byte over is refused, and it appears in no message.

## Acceptance

- Measured: PIT kills the mutants of `nextBytes`, `deactivate`'s check and the credential limits.
- Seen to fail: each case.

## Measured

PIT killed `nextBytes` through `HomeserverTokenTest`, `deactivate`'s check through `AdminRoomTest`, and the credential
limits through `RoomTest`, seen also by hand. `AdminRoom` lines 224 and 264 stay: wording of a failure already refused.

`Room`'s lines 176, 177 and 238 to 260 show as left, though the new cases were seen red by hand against them: those cases
run the code in a child JVM, which PIT does not mutate.
