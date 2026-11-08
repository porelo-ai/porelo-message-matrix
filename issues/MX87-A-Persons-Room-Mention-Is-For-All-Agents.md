# MX87 — A Person's Room Mention Is For All Agents

**Status:** Review.
**Size:** S - one condition in `Person`, its tests and the contract line Porelo reads.

**What must be true.** A person who mentions the whole room (`@room`, which the client sends as `m.mentions.room`) in
the project's room gets a person file marked `"all": true`, so Porelo hands it to every task, as for a line headed
`All agents:`.

## Plan

- **`Person.deliver` sets `all` also when the event's `m.mentions.room` is the JSON boolean `true`**; any other value
  (absent, `false`, a string) does not. It changes only who is woken, never the reach: the same field, with the same
  rules as the head's `All agents` - present and `true` or absent, never with `helpers`, `to` kept, asking the
  homeserver nothing.
- **A direct chat is not marked**, whatever it mentions: it is meant for its one task.
- **`doc/transport.md`** ("Its `all`") and **`doc/decisions.md`** name the room mention beside the head.
- **The test that goes red first:** `PersonTest`: a line `hi everyone` with `m.mentions.room: true` gives `"all": true`
  and asks nothing; today the field is absent. Beside it: `"room": false` and `"room": "true"` are not marked, a direct
  chat with a room mention is not marked.

## Acceptance

- The new test fails against the old code and passes against the new one.
- The unit suite passes.
