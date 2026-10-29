# MX30 — Only A Person Speaks As A Person

**Status:** Accepted.
**Size:** M - a setting in the transport's contract with porelo, the room's and the direct chats' poll, and an
integration test on the central homeserver.

**What must be true.** On a central homeserver, a task takes words as a person's - in the project's room and in
its direct chat - only from the people the project names, so the operator's direct chat with a task is the
operator's own channel: a question asked there and its answer reach that task alone, and no other machine's
account can speak in it as the operator.

## Why

A task's direct chat with a person is the channel for an agent's question to the operator: `send --to
@person:server` opens it from the task's account, and `poll --direct`, run with that task's token, hands what the
person says there to that task alone, marked `direct`. A second room per project would let every task read the
answer, so the direct chat is the shape; nothing new is needed for it.

What is missing is who counts as a person. Today a text without `org.fuin.porelo.message` is a person's
(`Poll`, `Person.deliver`), and a direct chat is taken from anybody who shares a room with the task's account
(`Direct.answerInvitations`, `sharesARoom`). On the account's own homeserver that is right: every account in the
room is this Porelo's or a person it joined. On a central homeserver the room also holds every other machine's
`@porelo.<M>`, relay and tasks, which the central homeserver's mode gives no more than their own rights. Such an account
can post a plain text in the room, which every task on this machine is handed as a person's word without the
filter, or open a direct chat with a task, which then reads as the operator's own channel.

## Plan

- **A setting `persons`** in `mail.transports.matrix`: the user ids whose words are a person's, listed by the
  project rather than inferred from who invited whom, which a homeserver's administrator can shape. Required with
  `homeserver`, refused without it (as `room` is); `Settings`, `SettingsCheck` and `doc/transport.md`.
- **`Person.deliver`** hands over only a sender `persons` names, else skips it and names it on stderr.
- **`Direct.answerInvitations`** takes an invitation only from a person `persons` names; any other is declined
  and named.
- **`send --to @user`** opens a direct chat only with a person `persons` names (78 otherwise), so a question is
  never asked of another machine's account.
- **Red first:** `CentralIT`, with a second machine's task account posting plain text in the room and opening a
  direct chat with a task of the first machine; today both are handed over.

## Acceptance

- On the central homeserver, a person named in `persons` speaks in the room and in a direct chat with a task, and
  each is handed over as today.
- Seen to fail: plain text from another machine's task account in the room, and a direct chat it opens, are
  handed over before the change and skipped and named after it.
- Settings with `homeserver` and no `persons` are refused (78), by `settings` too.
- On the account's own homeserver nothing changes.

## To be checked

- With Agent Core: whether porelo already checks a person file's `sender` against people it knows, and how it
  addresses an agent's question to the operator (`send --to` the operator's user id), on both kinds of
  homeserver; and whether `persons` reaches the transport unread, as `room` does.
