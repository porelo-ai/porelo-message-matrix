# MX111 — The Docs Say What The Code Does

```json
{
  "$schema": "issue-1.json",
  "id": "MX111",
  "title": "The Docs Say What The Code Does",
  "state": "Review",
  "size": "S",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "Every sentence of `README.md`, `doc/` and the CHANGELOG's latest section names only commands, flags, paths, defaults, states, messages and behavior the code on main has; where the code is wrong, a bug of its own"
}
```

**Why this size.** About 1300 lines of documents read once against the code, each finding checked by hand; no
behavior change.

**What must be true.** No user-facing document claims a verb, flag, path, default, exit code, message or behavior that
is gone, renamed or never was.

## Why

Operator order ST361: the documents drifted while the code moved (the tunnel's removal in [MX109](README.md#mx109) among the latest), and
a reader trusts them before the code.

## Plan

- A sub-agent on Sonnet reads `README.md`, `doc/index.md`, `doc/transport.md`, `doc/homeserver.md`,
  `doc/decisions.md` (its current decisions, not their history) and the CHANGELOG's `[Unreleased]` section page by
  page against the code on main, and lists each claim the code does not bear out with file:line, the claim, and the
  code it read. It runs no test suite and no VM.
- Each finding is checked in the code by this repository's agent; only a confirmed one counts.
- Where the code is right, the document is fixed. Where the document is right and the code is wrong, that is filed as
  its own bug (Backlog, estimated), and the document stays.

## Acceptance

- Every confirmed finding is fixed in the document or filed as a bug; the count is reported as claims checked /
  confirmed / fixed / filed as bugs.
- No change under `src/`.
- `-Pdocuments test`, `check-citations`, `check-doc-site`, `check-commits` and the local trace check are green.
