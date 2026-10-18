# MX23 — The Transport Takes Its Tokens On Its Input

**Status:** Backlog; the transport's half of porelo's
[B206](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b206), which closes what
[MX21](README.md#mx21) leaves open.
**Size:** M - one contract change, made here and in porelo together: every verb reads its secrets from its input
instead of the environment, `describe` says so, and the environment stays a fallback for a Porelo of the old way;
the tests of every verb that takes a token, `doc/transport.md`, and a VM run against a porelo that hands them so.

**What must be true.** The transport's tokens are never in its environment: no process of the account can read
them from `/proc/<pid>/environ`, not even in the moment before the transport's first step, which was measured as
readable in 25 of 300 starts.

## Why

Porelo hands a transport its secrets as its environment (`doc/transport.md`), and the kernel puts the environment
in `/proc/<pid>/environ` at `exec`, before the transport runs any code. [MX21](README.md#mx21) makes the running
transport not dumpable, which leaves 15 to 40 ms at each start in which another process of the account reads the
token; the relay polls often, so a process watching `/proc` takes one. Only a token that never is in the
environment closes it.

## To be checked

- The shape, with porelo's side: the secrets beside the settings on standard input
  (`{"settings": …, "secrets": …}`) for the lifecycle verbs, which read their settings there today; on fd 3 for the
  verbs whose input carries something else or nothing; the version in `describe` that says which way.
- That a Porelo of the old way still works: the environment read when the input holds no secrets, and how long
  that fallback stays.
- That nothing else of the transport reads a secret from the environment: `homeserver-token` and the settings
  `PORELO_MATRIX_CA_FILE` and `PORELO_MATRIX_TLS_VERIFY`, which are no secrets and may stay.
