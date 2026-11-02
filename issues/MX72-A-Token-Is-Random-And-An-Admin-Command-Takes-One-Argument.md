# MX72 — A Token Is Random And An Admin Command Takes One Argument

**Status:** Backlog.
**Size:** S - a few cases in three test classes.

**What must be true.** A homeserver token that stopped being random, an admin room command that took a user id unchecked, and a credential over its limit each fail a unit test.

## Plan

- `HomeserverTokenTest`: two tokens made in two directories differ, and neither is the encoding of zero bytes.
- `AdminRoomTest`: `deactivate` refuses a user id with a space or a line break and sends no command.
- `RoomTest`: a credential of exactly the limit passes; one byte over is refused, and it appears in no message.

## Acceptance

- Measured: PIT kills the mutants of `nextBytes`, `deactivate`'s check and the credential limits.
- Seen to fail: each case.
