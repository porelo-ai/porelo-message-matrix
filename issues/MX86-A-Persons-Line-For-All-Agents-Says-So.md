# MX86 — A Person's Line For All Agents Says So

```json
{
  "$schema": "issue-1.json",
  "id": "MX86",
  "title": "A Person's Line For All Agents Says So",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 10,
  "blockedBy": ["MX85"],
  "covers": "A person's line headed `All agents: …` is marked `\"all\": true` in its person file, so Porelo hands it to every task of the project"
}
```

**Why this size.** one field in the person file, set in `Person`, its tests and the contract; Porelo reads it.

**What must be true.** A person who writes `All agents: …` in the project's room gets a person file marked
`"all": true`, so Porelo hands it to every task of the project and wakes each.

## Why

A task is named in the room by its agent's name, and [MX85](README.md#mx85) makes `Agent Pi: …` name that task. The
agents address everybody with `All agents:`, and a person writing so means every task. Today such a line has `to: []`,
which tells Porelo nothing beyond "nobody in particular".

## Plan

- **`Person.deliver` marks a line whose head says `All agents`** by the room tool's rule (`MeantFor.head`'s
  broadcast: the phrase as a whole, in any case, before the first colon within 300 code points) with `"all": true`.
  The field is present and `true` exactly then, otherwise absent, never `false`, as `helpers` is. Deciding it asks
  the homeserver nothing.
- **A line for all agents is never for the helpers alone**: with `all`, `helpers` is not set, so Porelo does not
  answer it through the relay instead of waking the tasks. `to` keeps whom else the line names.
- **A direct chat is not marked**: it is meant for its one task.
- **`doc/transport.md`** ("A person's file") names the field; **`doc/decisions.md`** says the head's broadcast is read
  as the agents read it.
- **The test that goes red first:** `PersonTest`: `All agents: the Build is green` gives `"all": true` and asks
  nothing; today the field is absent. Beside it: `all agents:` counts too, `All agentsX:` and a broadcast after the
  colon do not, a line for all agents that names only a helper is not marked `helpers`, and a direct chat is never
  marked.

## Acceptance

- The new test fails against the old code and passes against the new one.
- The unit suite passes.
