# MX108 — The Issue Heads Are Checked Every Day

```json
{
  "$schema": "issue-1.json",
  "id": "MX108",
  "title": "The Issue Heads Are Checked Every Day",
  "state": "Implementing",
  "size": "XS",
  "priority": "Low",
  "rank": 6,
  "blockedBy": [],
  "covers": "The daily Shared rules check runs porelo-release's issue check on issues/, so a head the planner's board refuses, or an index out of the heads' order, turns main's daily run red"
}
```

**Why this size.** one step in one workflow, `.github/workflows/shared-rules.yml`; no code and no contract.

**What must be true.** An issue head in `issues/` that the shared issue schema refuses - a missing size, a rank of
`null` - or an index whose rows do not follow the heads is seen on `main` within a day, by a red *Shared rules check*
that names the file and the field.

## Plan

- **`.github/workflows/shared-rules.yml`** gets a step beside *Refuse a link to an issue's file and an issue number in
  any file*: `java -cp "$(cat "$RUNNER_TEMP/ci-tools.classpath")" org.fuin.porelo.release.Main issues check issues`,
  run on the schedule, a pull request and by hand, as the other checks of that job are; the tool comes from the
  released porelo-release the `ci-tools` profile already resolves, which has `issues check` today.
- **No changelog entry**: a change to the CI's own workflow only.
- **Proof:** the step is a check; its failure path is made to happen once - a copy of `issues/` with a head stripped
  of its `size` - and read, then the job runs green on the branch by hand before the hand-over.

## Acceptance

- The job's log shows the issue check reading `issues/` and passing on `main`.
- With a head missing its `size`, the step fails, naming the file and the field.
