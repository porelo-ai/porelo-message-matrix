# MX80 — The Workflows Run On A Forge As On GitHub

**Status:** Implementing.
**Size:** S - the workflows, `settings.xml`, `.mvn/` and `porelo.yml`, with the tests that read them; no code that
ships changes.

**What must be true.** This repository's workflows run unchanged and green on the host's Forgejo and on GitHub, on the
same commit: only the values of the setup's variables and secrets differ, nothing is published outward from the
forge, and a task's image is built from `porelo.yml` as Porelo reads it.

## Plan

Read in today's `build.yml`, `delete-runs.yml`, `shared-rules.yml`, `settings.xml`, `.mvn/` and `porelo.yml`, against
the host's local registry and publication settings.

- **The publication is switched by `vars.PORELO_PUBLISH`**, present on both forges, `true` on GitHub and `false` on the
  forge: the steps *Publish the packages* and *The packages are indexed* run only where it is `true`, and so does
  `delete-runs.yml`, which calls GitHub's API. A value other than `true` or `false` stops the Build at once. Today
  both steps run on every push to `main`, and on the forge would upload to a placeholder.
- **Snapshots are read from the forge's registry too:** `settings.xml` gains a profile reading
  `MAVEN_SNAPSHOTS_URL` with the token `PORELO_PACKAGES_READ_TOKEN`, beside Sonatype, the newest winning, with a
  filter in `.mvn/rrf/` keeping every group but this project's from Sonatype; on any server but GitHub a job stops at
  once when either value is empty, before its first Maven step. This repository deploys nothing there.
- **The homeserver in the forge's job:** the integration tests start the homeserver from its pinned image with podman.
  Where the runner offers no podman, the job takes the homeserver's binary out of the same image by its digest and sets
  `MATRIX_TEST_TUWUNEL`, as a task does; `LoopbackOwnerIT` then skips, which the hand-over names.
- **`porelo.yml` uses the keys Porelo reads, `from` and `run`**, and loses its note that no Porelo reads it.
- **`.mvn/maven.config` becomes tracked** with the filter's switch; a machine's own split of the local Maven repository
  then goes into `MAVEN_ARGS`.
- **Red first:** a test reading the workflows fails while a publishing step or `delete-runs.yml` runs without
  `PORELO_PUBLISH == 'true'`, while a Maven step runs before the forge's guard or without the read token, and while
  the guard lets a forge without its registry or token through, run as its own script.

## Acceptance

- The workflow tests fail on today's workflows and pass after.
- The Build and the *Shared rules check* are green on GitHub on the commit; on the host's forge, on the same commit,
  when the move's host is there.
- Seen to fail: each guard's script given a forge with an empty registry, an empty token or a `PORELO_PUBLISH` that
  is neither `true` nor `false`.
