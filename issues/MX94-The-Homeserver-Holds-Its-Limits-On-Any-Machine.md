# MX94 — The Homeserver Holds Its Limits On Any Machine

**Status:** Backlog.
**Size:** S - one podman flag in the unit and in the `backup`/`restore` run, and one test against the real
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

## Acceptance

- The unit's and the `backup`/`restore` run's container options set the open files (`--ulimit nofile=…`), with a
  reason for the value; the unit test that holds the container's bounds names it.
- A test starts the real homeserver with a process limit forced below what its uncapped pool needs on the machine it
  runs on, and sees it answer; with the pool cap taken out, the same test fails at the start - seen once.
