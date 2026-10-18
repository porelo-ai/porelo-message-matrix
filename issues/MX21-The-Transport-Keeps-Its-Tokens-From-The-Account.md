# MX21 — The Transport Keeps Its Tokens From The Account

**Status:** Implementing; found while planning porelo's
[B102](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b102) walk, the transport's side of
[B23](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b23).
**Size:** S - one module: a class that turns dumping off as `main` starts, its call from `Main`, the native image's
registration of the one foreign call, and an integration test against the native executable; no contract changes.

**What must be true.** While the transport runs, no other process of the same account can read its environment or
its memory: `/proc/<pid>/environ` and `/proc/<pid>/mem` of a running `porelo-message-transport-matrix` are refused
to the account, as they are for Porelo's daemon, broker and ssh agent.

## Why

Porelo hands the transport its secrets as its environment, as `doc/transport.md` says: a task's or the relay's
`PORELO_MATRIX_ACCESS_TOKEN`, and for the lifecycle verbs `PORELO_MATRIX_ADMIN_TOKEN`, the homeserver's
administrator. The transport is a fresh executable, so it is dumpable even though the daemon that starts it is
not, and any process of the account can read `/proc/<pid>/environ` while it runs; `poll --wait` stays up to 30 s.
**Harm:** with `@porelo`'s token, anything running as the account - an agent's tool that escaped, a script - can
deactivate every account, read every project's room and post as any task; with a task's, it can speak as that
task.

## Plan

1. `Hardening` (new, in `org.fuin.porelo.message.matrix`): `prctl(PR_SET_DUMPABLE, 0)` through the foreign function
   API, read back with `PR_GET_DUMPABLE`, and the start refused if it is still set, as porelo's
   `ProcessHardening.disableDumping` does.
2. `Main.main` calls it first, before any secret is read from the environment.
3. The native image: the downcall registered in
   `src/main/resources/META-INF/native-image/org.fuin.porelo/porelo-message-transport-matrix/reachability-metadata.json`,
   and native access enabled where the build needs it; `porelo-release`'s FFM check run over it.
4. **Red first:** `DumpableIT` starts the native executable's `poll --wait 30` against the test homeserver and reads
   its `/proc/<pid>/environ` as the same user; today that read succeeds and shows the token, after the fix it is
   refused.
5. The changelog line (`Security`), and `doc/decisions.md` names the choice beside the accepted risk of the
   unencrypted homeserver.

## To be checked

- **Measured:** the moment between `exec` and the `prctl` call is 15 to 40 ms under `strace`; a shell loop
  reading `/proc/<pid>/environ` right after each start caught the token in 25 of 300 starts. So this narrows the
  exposure from the whole run to that moment, and a process that watches `/proc` for the transport's starts can
  still take a token. Closing it needs the tokens off the environment, which changes Porelo's contract (L). As the
  operator decided, the moment is the accepted risk in `doc/decisions.md`, and the pipe is an issue of its own.
- **Answered:** only the native executable proves it. The tests in a JVM call `Main.run`, never `main`, and a
  JVM's `main` is not the process's first thread; `DumpableIT` runs under `-Pnative` only.
- **Answered:** `homeserver-token` goes through `main` like every verb, so it is hardened too, though it reads
  no token.
