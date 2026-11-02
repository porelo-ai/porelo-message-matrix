# MX66 — The Default Project Has No Room

**Status:** Implementing.
**Size:** S - one refusal where the lifecycle's options are read, its tests, the contract's table.

**What must be true.** A task of the project `default` never joins a room, whatever the machine or host has and whatever
settings reach the transport: the transport makes no room for `default`, makes no account in one, and admits nobody to
one. A room left over under that name can still be taken away.

## Why

`default` is the project that exists without a project: a task there talks to nobody. Porelo decides that first, but the
transport today treats the name like any other: `setup --project default` starts the account's own homeserver and makes
`#porelo-default`, or joins that room on the homeserver its settings name. So one wrong call, or a room peer left over in
a project file, would put such a task in a room.

## Plan

- `Lifecycle.options`: `--project default` refused with 78, before any settings are read or any request is made, for
  `setup`, `enroll`, `join` and `members`. `retire`, `part` and `clear` stay allowed, so what is left over can go.
- `doc/transport.md`: the rule in the verbs' table.
- Red first: `LifecycleTest`: each refused verb with `--project default` ends 78 and starts no homeserver. Today `setup`
  goes on to start one.

## Acceptance

- Measured: each refused verb ends 78, naming the project, with no homeserver started, no request made and nothing
  written.
- Measured: `clear --project default` still removes a room left under that name.
- Seen to fail: any refused verb with `--project default` makes an account or a room.
- `poll`, `send` and `read` take no project: they act only with the tokens and the conversation `setup` and `enroll`
  answered, so with no such answer there is nothing for them to act on.
