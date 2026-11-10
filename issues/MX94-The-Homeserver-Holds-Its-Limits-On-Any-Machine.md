# MX94 — The Homeserver Holds Its Limits On Any Machine

```json
{
  "$schema": "issue-1.json",
  "id": "MX94",
  "title": "The Homeserver Holds Its Limits On Any Machine",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 7,
  "blockedBy": [],
  "covers": "The local homeserver's container sets its open files too, and a test runs the real homeserver under a process limit its uncapped pool would break"
}
```

**Why this size.** one podman flag in the unit and in the `backup`/`restore` run, and one test against the real
homeserver; nothing another repository reads.

**What must be true.** The local homeserver starts and serves on a machine of any size, and its container takes no
more of the host than its own limits: processes, memory, CPU and open files are each set, none left to podman's
default. A test shows it under a process limit far below what an uncapped database pool would need.

## Why

Tuwunel sizes its database pool from the machine's cores; on a 32-core host it reached 2048 threads and podman's
default process limit refused its start. The unit and the `backup`/`restore` run already cap the pool, the read cache
and RocksDB's threads and run the container with a process, memory and CPU limit, and unit tests hold those values.
Two things are still open:

- **Open files have no limit of the container's own**, so they follow podman's default on whatever host runs it.
- **No test runs the real homeserver where its uncapped pool would fail.** The test machines have 4 to 16 cores, below
  the limit of 512 processes even without the cap, so removing the cap leaves every test green.

## Plan

- **`HomeserverBounds.PODMAN_FLAGS` gains `--ulimit=nofile=65536:65536`**, which the unit's `ExecStart` and
  `PodmanUnit.command` for `backup`/`restore` both take; `HomeserverUnitTest` holds the list and that both paths carry
  it. Measured: a fresh homeserver holds 154 files, one that served a dozen agents for weeks 256; the host's limit
  passed through today is 524288. RocksDB runs with `max_open_files=-1`, keeping every table file open, and the pinned
  Tuwunel has no setting to lower it, so the limit sits far above what a host's homeserver can reach: at the limit
  RocksDB stops writing until a restart.
- **`HomeserverBoundsIT` starts the pinned image with the unit's flags and settings, the process limit forced to 128
  and four of the cores the test may run on pinned with `taskset`** (Tuwunel counts the cores it may run on; a container's CPU limit does
  not change that, and rootless podman has no `cpuset`). Measured on four cores: capped 72 threads, uncapped 138;
  uncapped, the start fails with `Resource temporarily unavailable`, as on the 32-core host. It is skipped where no
  container starts and on fewer than four allowed cores, where an uncapped pool fits too. Podman picks the published
  port, and the test checks that the unit's process limit is the one it forced down.
- **The test that goes red first:** `HomeserverUnitTest` expecting the open-files flag; `HomeserverBoundsIT` with
  `TUWUNEL_DB_POOL_MAX_WORKERS` taken out, and with the process limit split into two arguments.
- **Not done, from the reading:** the IT runs podman without a timeout of its own, as every IT of this repository
  does through the same pattern, since `podman run -d` returns once the container started; and it keeps its own small
  process runner beside `Tuwunel`'s private one, which has no `taskset`.

## Acceptance

- The unit's and the `backup`/`restore` run's container options set the open files (`--ulimit nofile=…`), with a
  reason for the value; the unit test that holds the container's bounds names it.
- A test starts the real homeserver with a process limit forced below what its uncapped pool needs on the machine it
  runs on, and sees it answer; with the pool cap taken out, the same test fails at the start - seen once.
