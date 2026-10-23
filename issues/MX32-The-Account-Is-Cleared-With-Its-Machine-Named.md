# MX32 — The Account Is Cleared With Its Machine Named

**Status:** Review.
**Size:** XS - the shape of one verb, its tests and a line of the contract.

**What must be true.** `porelo vault clear` takes away what the transport keeps for the account - the local
homeserver, its database, port and token - also on a machine whose transport takes the machine's name, instead of
leaving it behind with a usage error.

## Plan

- Porelo adds `--machine <host>` to every lifecycle verb but `settings` for a transport that takes it
  (`TransportLifecycle.run`), so the account-level `clear` arrives as `clear --machine <host>`. `Lifecycle.run`
  takes `clear` as account-level only with no arguments at all; with `--machine` it falls to the project form,
  which requires `--project`, and exits 64. `vault clear` then ends 1 and the homeserver stays.
- Account-level `clear` also takes `--machine M`, checked as everywhere else and otherwise ignored: the account's
  own homeserver has no machine name. Any other argument without `--project` is still 64.
- **Red first,** in `LocalHomeserverTest`, the transport run as Porelo runs it, with stand-ins for `systemctl` and
  `podman` on its path: `clear --machine node-1` clears the account like a plain `clear` (64 today); malformed shapes
  and a project's `clear --project p --machine node-1` touch nothing. `DescribeTest`'s assertion that
  `clear --machine node-1` is 64 goes.
- `doc/transport.md`'s row for `clear` says it takes `--machine M`; the changelog gains its line under *Fixed*.

## Acceptance

- `clear --machine <name>` with no `--project` clears the account's own homeserver, seen red against today's code.
- `clear --machine` with a name no machine can have, and `clear --task t`, are still 64 and touch nothing.
- A project's `clear` that names its machine never reaches the account's, seen red with the branch widened.
