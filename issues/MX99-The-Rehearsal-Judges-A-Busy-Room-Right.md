# MX99 — The Rehearsal Judges A Busy Room Right

```json
{
  "$schema": "issue-1.json",
  "id": "MX99",
  "title": "The Rehearsal Judges A Busy Room Right",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 2,
  "blockedBy": [],
  "covers": "The room rehearsal judges a room with history, other persons, retired tasks and other logins right, and leaves no session or chat open"
}
```

**Why this size.** the rehearsal client and program in `src/test` and their tests; nothing that ships.

**What must be true.** The room rehearsal says `OK` only where the room behaved, and `FAIL` only where it did not, also
in a room with history, other persons, retired tasks and other logins on the same host, and it never leaves the test
person's session or a chat open behind it.

## Why

A reading of the room rehearsal's first version by another model (gemini-4-argon-high) found ways the
rehearsal can misjudge a real host. The runs on the foo-org host held, in a young room with one person and three tasks;
a busier room would not.

## Plan

As built, the plan reading's answers taken in (see *Read*):

- **A session ends however a run ends**: `close` clears the thread's interrupt around the logout and gives it back; at
  the end of the input with a session still open, the run logs out, checks `whoami` as `log-out` does, says no step
  line for it, and fails if the session survived. Every logout first leaves the direct chats the session opened and
  did not leave. An interrupted pause in `RoomRehearsal` is a `Failure`, and an interrupt stops the run at once
  (`STOP interrupted`).
- **Helpers of the room's own logins pass**: `@porelo`, `@porelo.<login>` and `@porelo-relay-<name>.<login>` of the
  person's login and of every login named with `--person`; another login's helper in the room is a stranger. Members
  are the joined and the invited.
- **The person joins first**: `RoomRehearsal` joins the room after signing in, so a room the host only invited the
  person to is rehearsed, not failed.
- **A pill names a task by itself**: a scenario with a pill and no head. The lines for every task name the first task
  too (`Agent Pi, All agents: …`, `Agent Pi: @room …` with the room mention), so a lost "All agents" or `@room` wakes
  only that task and fails. The fake room reads heads, mentions and `@room`.
- **Reading reaches back to the line**: `messages` asks only room messages (`filter`), pages back until the line
  waited on, at most 100 pages of 100, and stops at an empty page, a missing or an unchanged `end`; a line the room does
  not hold fails the wait. The order check reads back to the run's first line; a line read twice is out of order.
- **Only a new text line answers**: an `m.text` line that is no edit (`m.replace`) and no thread's line; a redacted
  line has none. `read` shows only such lines.
- **A busy room passes**: senders before the run's first line are not judged; a relay of a room's login passes only
  with a signed line (`org.fuin.porelo.message` and `org.fuin.porelo.signature`); further persons are named with
  `--person`.
- **A line for one task**: every task's record has it before the quiet time starts, so a late poll's answer is seen;
  then any sender but the task named carrying the line's nonce fails, task or not, and the task answering twice fails.
  A line for every task, and a direct line, fail the same way when answered twice or by an account not asked. A direct
  line answered aloud in the project's room since the run's first line fails.
- **`read` answers one line per step**, its messages in an array, as every other step does.
- **The same failure stops the run** when only an event's id differs; a line for every task waits once, not once per
  task. A failure is printed on one line, with the person's session and login token replaced by `<token>`; a failed
  step is named by its keyword only.
- **Arguments**: a task named twice, by name, account or display, is refused; a display must be one porelo reads in a
  head (`Agent <Name>`, optionally ` (<short>)`); `--person` is a login's account, not a task's or a helper's, each
  once; all accounts on one server; `--socket`, `--room`, `--answer-within` and `--quiet-for` once each. Tasks compare
  by value.
- **Tests**: `RoomRehearsalTest` and `RehearsalClientTest` hold each of the above, the whole run of `RoomRehearsal`
  over the scripted gate among them (its join before the first line, its exit 65 on a failure, its logout, a task
  named twice refused before any request). Each was seen red with the line it guards broken.

## Read

The plan was read by gemini-4-argon-high (2026-11-10). Taken in: bounded paging and its stop on an unchanged token;
the order check reading back to the run's first line and failing a line read twice; helpers of named persons' logins;
retired tasks are strangers while they are still joined; a relay's line only when signed, invited members; edits,
threads and redactions answer nothing, a double answer fails, deliveries before the quiet time, any sender with the
nonce fails, a direct answer aloud fails; the lines for every task name one task; direct chats left at every logout and
around an interrupt; an interrupt stops the run, the logout at the end of the input says no line; tokens scrubbed and
a failure on one line, a failed step named by its keyword; the arguments' shapes and repeats. Left out, with the
reason:

- `wait-answer` keeps matching any line of the sender after the waking line: porelo-host's move check reads that step,
  and a nonce in it is a change of that contract.
- The client's step lines (`sign-in`, `join`, `log-out`) stay on the rehearsal's standard output: they are JSON lines,
  told apart from `OK`, `FAIL` and `STOP` by their first character.
- A delivery keeps its own answer time after the answer's: two windows, each bounded, are what the scenario waits for.

The code was read by gemini-4-argon-high (2026-11-10), its eight points taken in: logins read from the persons'
localparts, so a server name with a dot holds its helpers, and `--person` by porelo's login rule; a direct line's nonce
looked for in the room only since the run's first line; a line for every task and a direct line fail when answered
twice or by an account not asked; two lines given one id fail; a signed line needs both fields filled; a text line
needs a body; an interrupted run logs out through `close` and ends 75, a `talk log` is ended when interrupted, and a
direct step's own failure outlives a failed leave; the unused `join` of the room's interface removed; the tests it
named as staying green each seen red.

## Not proven live

By the operator's word (2026-11-10 13:27Z) the rehearsal runs on a host with one person, the test organization's; a
second person in the room is held by the unit tests only. Its live run goes on the next rented server, with
porelo-host's HO158 phase 2.

## Acceptance

- Each finding has a test that fails without its fix, and a rehearsal on a host with a retired task in the room holds
  every step.
