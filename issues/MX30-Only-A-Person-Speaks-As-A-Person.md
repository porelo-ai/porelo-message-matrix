# MX30 — Only A Person Speaks As A Person

**Status:** Review since 2026-10-30; Estimated and Backlog (operator, 2026-10-30, sprint 6m).
**Size:** S - shrank from M: a person is known by the shape of their account, so no setting and no change in
Porelo; the room's and the direct chats' poll, `send`, and the central account's secrets.

**What must be true.** On a central homeserver, a task takes words as a person's - in the project's room and in
its direct chat - only from a person's own account, so the operator's direct chat with a task is the
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

Since every person is a host login, a person's own account is `@<login>`: a bare login, with no dot, and never
`porelo` or `porelo-…`, while every task, relay and provisioning account holds a dot. That shape replaces the
`persons` setting, so Porelo needs no change.

- **A central account is marked** by `PORELO_MATRIX_LOGIN` in the `secrets` that `setup` and `enroll` answer
  on a central homeserver; `poll` and `send` read it from their environment.
- **`Person.deliver`** hands over only a sender of that shape on the polling account's server, else skips it
  and names it on stderr.
- **`Direct.answerInvitations`** declines an invitation from any other account and names it.
- **`send --to @user`** opens a direct chat only with such an account (78 otherwise).
- **Red first:** `LoginTest`, and cases in `PersonTest`, `DirectTest` and `SendTest` with another person's task,
  relay and the administrator.

## Acceptance

- On the central homeserver, a person's own account speaks in the room and in a direct chat with a task, and
  each is handed over as today.
- Seen to fail: plain text from another machine's task account in the room, and a direct chat it opens, are
  handed over before the change and skipped and named after it.
- On the account's own homeserver nothing changes.
