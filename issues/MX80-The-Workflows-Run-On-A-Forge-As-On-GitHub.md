# MX80 — The Workflows Run On A Forge As On GitHub

```json
{
  "$schema": "issue-1.json",
  "id": "MX80",
  "title": "The Workflows Run On A Forge As On GitHub",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 16,
  "blockedBy": [],
  "covers": "The workflows run unchanged and green on the host's Forgejo and on GitHub: publication and snapshot registry switched by the setup's variables, the homeserver in the forge's job, `porelo.yml` in the keys Porelo reads"
}
```

**Why this size.** the workflows, `settings.xml`, `.mvn/` and `porelo.yml`, with the tests that read them, taken over from
porelo-message-sluice's forge-ready workflows where they exist; no code that ships changes.

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
  filter in `.mvn/rrf/` asking Sonatype for this project's group alone; on any server but GitHub a job stops at once
  when either value is empty, before its first Maven step, and every Maven step holds the token that only reads. This
  repository deploys nothing there. The *Shared rules check* runs `check-snapshot-filter`.
- **The homeserver in the forge's job:** the integration tests start the homeserver from its pinned image with podman.
  The forge's runner image has none (measured on its pinned `act-24.04` image: no podman, no `rpm`; `curl`, `jq`,
  `tar` and `zstd` are there), so there the job fetches the image `homeserver/Dockerfile` pins from `ghcr.io` with
  curl, checks the index, the linux/amd64 manifest and each layer against its digest, takes `/usr/bin/tuwunel` from
  it and sets `MATRIX_TEST_TUWUNEL`, as a task does; `LoopbackOwnerIT` then skips. Run in that runner image, the
  binary is byte for byte the one podman starts here; a digest that is not the pin's stops the step. The registry's
  token and every digest it answers are checked for their shape before they are used, and each layer is listed into a
  file, so a layer that cannot be read stops the step and a later layer's binary always replaces an earlier one's.
  `HomeserverStepTest` runs the step's own script against a stand-in for the registry.
- **`porelo.yml` uses the keys Porelo reads, `from` and `run`**, and loses its note that no Porelo reads it.
- **`.mvn/maven.config` becomes tracked** with the filter's switch; a machine's own split of the local Maven repository
  then goes into `MAVEN_ARGS`.
- **The workflows' reader and its tests are porelo-message-sluice's**, `WorkflowSteps` and `WorkflowTokensTest`,
  taken over with the reader also reading a `with:` value written over several lines; every script is written as
  `run: |`, which the reader requires, and refusing a step with two `env:` or `with:` blocks. `WorkflowPublishTest`
  holds the publication's switch: every line naming the publication lies in the two gated steps, whose conditions
  have no `||` outside parentheses, and only `delete-runs.yml`'s one job calls GitHub's API. `WorkflowTokensTest`
  also holds the exact Maven steps and the exact values `settings.xml` reads from the environment.
- **Red first:** a test reading the workflows fails while a publishing step or `delete-runs.yml` runs without
  `PORELO_PUBLISH == 'true'`, while a Maven step runs before the forge's guard or without the read token, and while
  a guard lets a forge without its registry or token, or a switch that is neither value, through, run as its own
  script.

## Acceptance

- The workflow tests fail on today's workflows and pass after.
- The Build and the *Shared rules check* are green on GitHub on the commit; on the host's forge, on the same commit,
  when the move's host is there.
- Seen to fail: each guard's script given a forge with an empty registry, an empty token or a `PORELO_PUBLISH` that
  is neither `true` nor `false`.
