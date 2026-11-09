# MX98 — An Answer Goes Into The Chat The Person Wrote In

**Status:** Backlog.
**Size:** S - which of a task's direct chats `send --to <person>` writes into, and its tests; no change to Porelo.

**What must be true.** A task's answer to a person's direct line goes into the chat the person wrote that line in, so
the person reads it where they asked, however many chats with the task they still have open.

## Why

In the rehearsal on a host a person opened a second direct chat with a task, wrote there, and the task answered at
once (`talk log`: `taken`, `queued`, `sent` 14 s after the line was delivered), yet the answer never showed in that
chat: `Direct.chatWith` takes the first room of the task's `m.direct` the person is still joined or invited in, the
oldest, and wrote it there. A person who keeps an older chat open, as every client allows, waits in the new one for an
answer that went elsewhere.

## Plan

- **`send --to <person>` writes into the open chat the person last wrote in**: among the task's `m.direct` rooms with
  that person joined or invited, the one whose newest message from the person is newest (each read back a page with
  `/messages?dir=b`, a room the task cannot read skipped); when the person wrote in none, the one remembered last; when
  none is open, a new one as today.
- **Nothing else changes**: which rooms count as direct chats, how an invitation is taken, and that an answer opens a
  chat when none is open.
- **The test that goes red first:** `SendTest` against a scripted homeserver: two open chats, the person's newest line
  in the second, the answer goes to the second; the newest in the first, to the first; no line in either, to the one
  remembered last; a chat the person left is never written. `DirectIT` against the real homeserver: a person with two
  open chats writes in the newer, and the task's `send` lands there.

## Acceptance

- The rehearsal's direct-chat step holds on a host while the person keeps an older chat with the task open.
