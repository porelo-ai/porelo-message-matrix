# MX33 — A Person Is Shown By Their Name

**Status:** Review.
**Size:** XS - one setting of the homeserver's unit.

**What must be true.** A person who joins the conversation is shown, in the room and in every mention of them, by
the name they joined with, nothing added.

## Plan

- Tuwunel adds its `new_user_displayname_suffix`, an emoji by default, to every account it registers, so
  `porelo talk join` for `reader` gave the display name `reader 💕`, and every mention of the person read so. The
  unit sets `TUWUNEL_NEW_USER_DISPLAYNAME_SUFFIX` empty.
- It only reads wrong, so it gets no test. Measured on the pinned image: a new account's profile reads `reader 💕`
  without the setting and `reader` with it.

## Acceptance

- On the local homeserver, a person joined with `porelo talk join` has the display name they joined with.
