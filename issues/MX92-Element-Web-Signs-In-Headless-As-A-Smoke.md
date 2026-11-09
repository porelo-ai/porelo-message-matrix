# MX92 — Element Web Signs In Headless As A Smoke

**Status:** Backlog; blocked by [MX89](README.md#mx89).
**Size:** M - a headless browser and an Element build in a test VM with no desktop, one scripted path, and its
upkeep across Element's releases; optional, never a gate of a rehearsal.

**What must be true.** Beside the scripted client, an optional smoke shows that Element itself, as a person uses it,
signs in through the host's gate, sends a line with a pill, and shows a task's answer.

## Plan

- **In a test VM with no desktop of the operator:** Chromium headless driven by Playwright, an Element web build of a
  pinned version and digest served at the forward's own page, the forward to the gate made as the test person's host
  login with the test person's key from an agent forwarded for the run alone.
- **One path:** *Continue with SSO*, open the project's room, type `Agent <A>: ` and pick A from the pill list, send,
  wait for A's answer. Checked on the host by `porelo talk log <task>`; a screenshot on failure.
- **It records the event Element sent**, so the event shapes `RehearsalClient` sends can be compared with Element's.
- **Not a gate:** a failure is reported beside the run's result and stops nothing.
- **The test that goes red first:** the path against a host whose gate refuses the forward's page (a wrong `Host`)
  fails at sign-in, naming the step.

## Acceptance

- On a local VM host: the path runs green, the recorded event matches the shape `RehearsalClient` sends for a pill,
  or the difference is written into the rehearsal's report.
- The VM shows nothing on the operator's display.
