# MX34 — The Homeserver Starts On A Large Machine

**Status:** Implementing.
**Size:** S - One transport module, its launch template and regression tests; no transport contract change.

**What must be true.** The local homeserver starts, and `backup` and `restore` run, on a machine of any size: on a
rented host of 32 or more cores as on a small VM, with nothing set by hand on that host. It is this repository's
part of [PJ68](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj68), every container fitting
the machine it runs on. A bug, done first, before the next test on a rented host. It covers, as decided:

- Tuwunel's database pool capped at 64 workers, in the unit and in the one-shot run for `backup` and `restore`;
- its read cache and memory bounded, not grown with the cores;
- limits of the homeserver's container of its own: pids, memory and CPU;
- a test that goes red without the cap and green with it.

## Why

- On a rented host of 32 cores, Tuwunel 1.9.4 sizes its database pool from the block
  device's queues and the cores, up to its own cap `db_pool_max_workers = 2048`. Podman's default `pids_limit` is also
  2048, so with Tuwunel's other threads the spawn failed with "Resource temporarily unavailable (os error 11)". The
  unit was refused five times and the first task start rolled back. A VM of 6 cores stays far below the cap, so no run
  showed it.
- On the VM, a containers override with `pids_limit = 60` failed the unit with the host's
  words; the same limit with `env = ["TUWUNEL_DB_POOL_MAX_WORKERS=8"]` started it. On the host, a user drop-in giving
  `TUWUNEL_DB_POOL_MAX_WORKERS=64` through `CONTAINERS_CONF_OVERRIDE` brought it up with `total_workers=64`.

## Plan

- `HomeserverBounds` holds an immutable ordered map of Tuwunel settings: database workers 64, database read cache
  256 MB, LRU cache modifier 0.5 and RocksDB parallelism 2. The pinned configuration accepts a floating-point cache
  modifier below 1.0. The modifier reduces core-scaled LRU capacities; the container's memory limit bounds total use.
- `PodmanUnit.command` and the integration stand-in `ProcessUnit` read the map. The unit template carries the same
  settings. Both container launch paths pass `--pids-limit=512 --memory=1g --cpus=2` before the image; `ProcessUnit`
  starts the binary directly and receives only the Tuwunel settings.
- `HomeserverUnitTest` and `PodmanUnitTest` fail first on missing settings and container flags, then pass when both
  launch paths carry them. The pool-margin test also fails with a deliberate 512-worker cap before restoring 64.

## Acceptance

- Both launch paths carry every shared setting and container flag before the image, and the pool cap stays below
  the PID limit independently of the host's core count. All existing unit tests continue to pass.
- The local homeserver starts and backup and restore complete on a VM with these limits.

## Measured

- **On a VM of Ubuntu 26.04 (6 cores, the account's controllers `cpu memory pids` delegated, as Ubuntu does by
  default):** the built unit came up through a task start's `setup`. Its container read `PidsLimit 512`, memory
  1 GiB and 2 CPUs, carried the four settings, and used 95 MB and 60 threads at rest. After a `backup`, a `restore` into
  an emptied database ran Tuwunel's one-shot container and brought the room back: the person logged in with their old
  password and was in the room.
- **Still a host's prerequisite:** a host whose user manager does not delegate the `cpu` and `memory` controllers
  refuses `--cpus` and `--memory`, so the homeserver would not start there.
