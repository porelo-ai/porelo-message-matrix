# MX80 — The Workflows Run On A Forge As On GitHub

**Status:** Idea.

**What must be true.** This repository's workflows run unchanged and green on the host's Forgejo and on GitHub, on the
same commit: only the values of the setup's variables and secrets differ, nothing is published outward from the
forge, and a task's image is built from `porelo.yml` as Porelo reads it.

## The shape

Read in today's `build.yml`, `delete-runs.yml`, `shared-rules.yml`, `settings.xml`, `.mvn/` and `porelo.yml`, against
the host's local registry and publication settings.

- **The publication is switched by `vars.PORELO_PUBLISH`**, present on both forges, `true` on GitHub and `false` on the
  forge: the steps *Publish the packages* and *The packages are indexed* run only where it is `true`, and so does
  `delete-runs.yml`, which calls GitHub's API. A value other than `true` or `false` stops the Build at once. Today
  both steps run on every push to `main`, and on the forge would upload to a placeholder.
- **Snapshots are read from the forge's registry too:** `settings.xml` gains a profile reading
  `vars.MAVEN_SNAPSHOTS_URL` with `secrets.PORELO_PACKAGES_READ_TOKEN`, beside Sonatype, the newest winning, with a
  filter in `.mvn/rrf/` keeping every group but this project's from Sonatype; the Build stops at once when either value
  is empty. This repository deploys nothing there.
- **The homeserver in the forge's job:** the integration tests start the homeserver from its pinned image with podman
  today. Whether a job on the forge's runner can start a container is measured first; where it cannot, the job takes
  the homeserver's binary by the same digest and sets `MATRIX_TEST_TUWUNEL`, as a task does, and
  `LoopbackOwnerIT` skips there, which the hand-over names.
- **`porelo.yml` uses the keys Porelo reads, `from` and `run`**, and loses its note that no Porelo reads it.
- **`.mvn/maven.config` becomes tracked** where the filter needs its switch; this machine's split of the local Maven
  repository then moves to `MAVEN_ARGS`, as for other repositories that track that file.

## To be checked

- Whether the forge's runner image offers what the Build installs or expects: `JAVA_HOME_25_X64`, `rpm`,
  `zlib1g-dev`, `dpkg-deb`, `objdump`.
- Where the workflow before its change is seen to fail on a forge: on the move's host, or on a local forge if one can
  be set up on a VM.
