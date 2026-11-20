# MX115 — Uninstalling Keeps The Conversation For A Later Install

```json
{
  "$schema": "issue-1.json",
  "id": "MX115",
  "title": "Uninstalling Keeps The Conversation For A Later Install",
  "state": "Analyzed",
  "size": "M",
  "priority": "High",
  "rank": 3,
  "blockedBy": [],
  "covers": "`clear --keep` leaves the account's conversation as one backup in a kept folder of its own and removes everything else; the first start after a later install restores it, and `clear` without `--keep` removes the kept folder too"
}
```

**Why this size.** A new option on `clear` and a restore on the first `setup`, both over `backup` and `restore`, which
exist; their tests against the real homeserver, on this computer and behind a host's gate; one contract section.

**What must be true.** A person who uninstalls Matrix while a project's room lives on it chooses: keep the
conversation for a later install, or delete everything. Kept, a later install shows the same rooms with their history,
the same people and tasks in them, and nobody's old token works. Deleted, nothing of it is left.

## Why

Removing the package touches no account's data: it has no script that does, and the database is a podman volume of
the account. So "keep" could leave everything where it is, but then Porelo's vault must keep the administrator's and
the tasks' tokens across the uninstall, and a stopped homeserver's state has to stay consistent with them. A
backup is self-contained: `restore` already takes it up before the first `setup`, which takes `@porelo`, the relay
and each task back with new tokens, so nothing kept in a vault matters.

## Plan

- **`clear --keep [--login L]`**, on the account's own homeserver once no project runs a task: a `backup` into
  `$XDG_STATE_HOME/porelo/matrix/kept/` (0700, the account's alone, since it holds every token and password hash),
  with the gate's `expelled-logins` beside it on a host, so an expelled login stays out; then `clear` as today, which
  leaves `kept/` alone. The homeserver not running: started for the backup, as `expel` starts it, and stopped again.
  A `kept/` already there is replaced only after the new backup is whole. Its answer names what was kept and where.
- **`clear` without `--keep`** removes `kept/` too, so "delete everything" leaves nothing.
- **The first `setup` after an install** finds no database and a `kept/`: it runs `restore kept/` first, puts
  `expelled-logins` back, then goes on as after any restore; once `setup` ended 0, `kept/` is removed. A restore that
  fails leaves `kept/` as it is and ends 78, saying the kept conversation could not be taken up; a fresh start then
  needs `clear` without `--keep`.
- **On a host**, the same commands as the homeserver's account; `gate --prepare` runs again after the restore, as
  `doc/homeserver.md` already says for a reset database.
- **In the service shape from the start:** the verb's entry point only parses, maps, calls one service method and
  prints; the service reads no environment, stdin or arguments, as the move of the other verbs into services asks.
- **Red first:** an integration test that makes a room with a task, a person and lines, runs `clear --keep`, checks
  that only `kept/` is left, then `setup` and `members` and `room read --all` give the same room, members and lines,
  and the task's old token is refused (401).
- **Contract:** `clear --keep` in "The lifecycle", the first start's restore in "Keeping the conversation", and
  `doc/homeserver.md`'s "Resetting it"; changelog under Added.

## Acceptance

- After `clear --keep`, the account holds `kept/` and nothing else of the homeserver: no unit enabled, no volume, no
  `homeserver.conf`, no positions; after `clear`, not even `kept/`.
- A later install's first task start shows the same room with its history in Element, the same members, and the old
  tokens refused; an expelled login on a host still cannot sign in.
- Media are not kept, as with every backup; the uninstall's choice says so.

## To be checked

- With Agent Core: Porelo's uninstall runs `clear --keep` or `clear` by the person's choice, and keeps no vault
  entry of the transport in either case.
- Whether `setup` on a computer whose `homeserver.conf` is gone takes the kept server name and port from the backup's
  copy, so account ids stay the same.
