# MX78 — A Backup Knows Its Own Snapshot

```json
{
  "$schema": "issue-1.json",
  "id": "MX78",
  "title": "A Backup Knows Its Own Snapshot",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 3,
  "blockedBy": [],
  "covers": "A copy holds all its positions name however many earlier snapshots still run, and a snapshot finishing during the copy does not fail it for good"
}
```

**What must be true.** However many earlier snapshots are still unfinished when `backup` starts, the copy it reports holds
all its positions name, and a snapshot finishing during the copy does not fail it for good.

## Why

- **`backup` covers one earlier snapshot still running, not two.** The homeserver makes snapshots in signal order and names
  none by its signal. One backup that gives up during its second wait leaves two running. A poll moves on, and the next
  backup copies the newer positions and takes the second old snapshot for its own, so a restore loses what came between.
- **A snapshot still running during the copy thins the oldest one**, and copying its files then fails with 78 rather than
  a temporary 75, though the next hour's run would succeed.

## To be checked

- That snapshots stay in signal order with a database large enough that one takes seconds, on a test VM.
- How long a snapshot takes at the size a project reaches.
- Whether the backups' own directory shows a snapshot in progress, or a snapshot's id can be known before it finishes, so a
  backup could wait for everything earlier rather than guess.
