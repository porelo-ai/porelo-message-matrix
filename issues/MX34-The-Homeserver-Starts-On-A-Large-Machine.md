# MX34 — The Homeserver Starts On A Large Machine

**Status:** Accepted.

**What must be true.** The local homeserver starts, and `backup` and `restore` run, on a machine of any size: on a
rented host of 32 or more cores as on a small VM, with nothing set by hand on that host. It is this repository's
part of [PJ68](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj68), every container fitting
the machine it runs on. A bug, done first, before the next test on a rented host. It covers, as decided:

- Tuwunel's database pool capped at 64 workers, in the unit and in the one-shot run for `backup` and `restore`;
- its read cache and memory bounded, not grown with the cores;
- limits of the homeserver's container of its own: pids, memory and CPU;
- a test that goes red without the cap and green with it.

## To be checked

- **Seen on 2026-10-23, on a rented host of 32 cores:** Tuwunel 1.9.4 sizes its database pool from the block
  device's queues and the cores, up to its own cap `db_pool_max_workers = 2048`. Podman's default `pids_limit` is also
  2048, so with Tuwunel's other threads the spawn failed with "Resource temporarily unavailable (os error 11)". The
  unit was refused five times and the first task start rolled back. A VM of 6 cores stays far below the cap, so no run
  showed it.
- **Measured on the VM the same day:** a containers override with `pids_limit = 60` failed the unit with the host's
  words; the same limit with `env = ["TUWUNEL_DB_POOL_MAX_WORKERS=8"]` started it. On the host, a user drop-in giving
  `TUWUNEL_DB_POOL_MAX_WORKERS=64` through `CONTAINERS_CONF_OVERRIDE` brought it up with `total_workers=64`.
- **Two places start Tuwunel:** the unit (`homeserver/porelo-matrix-homeserver.service`) and the one-shot run for
  `backup` and `restore` (`PodmanUnit.command`). The drop-in reached only the unit, so both need the cap.
- What grows with the cores besides the pool: the read cache (`db_cache_capacity_mb` = 128 + 64 × cores), the LRU
  caches, and RocksDB's threads (`rocksdb_parallelism_threads`, every logical CPU). Each gets a bound read in Tuwunel's
  configuration at the pinned revision.
- The container's `--pids-limit`, `--memory` and `--cpus`, sized for one account's homeserver on loopback, and how
  the test holds them on any core count without a large machine: read from what both places pass, and the VM's red
  run with a forced small limit.
