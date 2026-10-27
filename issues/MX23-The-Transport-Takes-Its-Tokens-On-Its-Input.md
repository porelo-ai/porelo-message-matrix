# MX23 — The Transport Takes Its Tokens On Its Input

**Status:** Implementing; the transport's half of porelo's
[B206](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b206), which closes what making the
running transport not dumpable leaves open.
**Size:** M - one contract change, made here and in porelo together: every verb reads its secrets from its input
instead of the environment, `describe` says so, and the environment stays a fallback for a Porelo of the old way;
the tests of every verb that takes a token, `doc/transport.md`, and a VM run against a porelo that hands them so.

**What must be true.** The transport's tokens are never in its environment: no process of the account can read
them from `/proc/<pid>/environ`, not even in the moment before the transport's first step, which was measured as
readable in 25 of 300 starts.

## Why

Porelo hands a transport its secrets as its environment (`doc/transport.md`), and the kernel puts the environment
in `/proc/<pid>/environ` at `exec`, before the transport runs any code. Making the running
transport not dumpable, which leaves 15 to 40 ms at each start in which another process of the account reads the
token; the relay polls often, so a process watching `/proc` takes one. Only a token that never is in the
environment closes it.

## Plan

The contract agreed with Agent Core on 2026-10-27 for porelo's
[B206](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b206):

- **One object on standard input**, `{"settings": {...}, "secrets": {...}}`, closed once written. `settings` comes to
  a lifecycle verb and to `settings`. `secrets` is the whole map the transport printed and Porelo kept, key for key,
  the keys that are no secrets included; with it, Porelo puts none of them into the environment.
- **`describe` says `"secrets": "input"`**; a Porelo of the old way still hands the bare settings on standard input
  and the secrets as the environment, and that keeps working.
- **No mixing:** with `secrets` in the input, no `PORELO_MATRIX_*` key is read from the environment, not even one the
  input lacks.

Read in the code: every verb reaches its secrets through the `env` map `Main.run` hands it (`Config.from`, the
lifecycle verbs' `env.get`), and the lifecycle verbs and `settings` read their settings with `Settings.read(in)`.
So one new class, `Input`, resolves both in `Main.run` before the verb runs:

- For the lifecycle verbs, `settings`, `check`, `send`, `poll`, `read`, `receipt` and `rename`, it reads standard
  input to its end, at most 1 MiB. An object with `settings` or `secrets` is the new way, any other key beside
  them refused: the verb gets `settings` (`{}` when absent) as its settings, and with `secrets` an environment
  with every `PORELO_MATRIX_*` key removed and the input's `secrets` laid over it. Anything else is the old way and passes through unchanged: the bare
  settings for a lifecycle verb, ignored by the others, which never read their stdin there.
- A secret's key that does not start with `PORELO_MATRIX_`, or a value that is not a string, is refused (78), so
  the input never sets `PATH`, `HOME` or the like.
- The warning for `PORELO_MATRIX_TLS_VERIFY=off` moves after the resolution, so it reads the input's value.
- `describe`, `room`, `homeserver-token`, `publish`, `backup` and `restore` read no input, as today: `room` is run
  by a person and refuses tokens in its environment, and the others get no secret.

- A terminal on stdin is not read, so a person running a verb by hand is not kept waiting.

Tests, red first: `InputTest` (the two ways told apart, no mixing, the refused keys and values, the size bound),
`MainTest` (a verb that finds its token on the input alone, and one that reads no environment token beside the
input's), `DescribeTest` (`secrets`), and `DumpableIT` with the token on the input: in 300 starts of the native
executable, `/proc/<pid>/environ`, read from the moment the process exists, never holds it. `LifecycleIT` runs
`setup`, `enroll` and `poll` once the new way. `doc/transport.md` and `CHANGELOG.md` change with it.

- **Found while building:** a verb that reads its stdin to the end waits while stdin stays open, as a person's terminal
  does, so a terminal is not read. A Porelo of the old way may hand the bare settings to every verb, so a verb that
  takes no settings ignores anything there but the new way's object, as it always did.
- **Declined after the reading:** a new way's object without `secrets` reads the environment, as agreed with
  porelo's side, so a Porelo that has kept nothing yet still works. `clear` without `--project` reads no settings,
  as before. A key twice inside `settings` is refused (78) instead of becoming a finding of `settings`: Porelo writes
  the object with a JSON library and never repeats a key.
