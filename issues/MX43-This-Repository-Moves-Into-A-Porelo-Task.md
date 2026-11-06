# MX43 — This Repository Moves Into A Porelo Task

**Status:** Analyzed; blocked by [MX80](README.md#mx80).
**Size:** S - nothing to build in this repository's code once [MX80](README.md#mx80) is done; the work is the image's
dry run, the trial move's steps and this issue worked to Review from inside the task: about 35 minutes of the agent's
work, 15 for the dry run here and 20 in the task, beside the waits for the image, the suite, the forge's Build, the
reading from outside and the way back.

**What must be true.** porelo-message-matrix is worked by its own agent inside a Porelo task of the project `porelo` on
the rented host: the agent takes this issue from start to Review in the task, its pushes reach the host's forge, the
forge's build is green, and it is reached in the project's room under this repository's name.

## Plan

Read in today's `porelo.yml`, `settings.xml`, `.mvn/`, the four workflows, the integration tests, the host's forge
setup and its local registry settings, and the project's procedure for an agent's move.

- **What moves:** this repository alone, as a trial: it works in the task on the move's host until its check is
  green, then takes the way back and is worked on this machine again until the real move of every repository. The
  agent in the task is Claude Code with the operator's subscription from the host's vault, named `Agent Matrix` after
  the nickname `Matrix` in porelo-project's `project.yml`.
- **What must hold first, in [MX80](README.md#mx80):** the workflows run on the host's forge as on GitHub (the
  registry and the publication switched by the setup's variables, the homeserver's binary in the runner's job, since
  the job starts no container), and `porelo.yml` uses the keys Porelo reads, `from` and `run`; today it still says
  `base_image` and `snippet`, which no task image is built from.
- **The image:** `porelo.yml` names Ubuntu 24.04 by digest, the pinned GraalVM, the C toolchain, `rpm`, `systemd` for
  `systemd-socket-activate`, and the homeserver's binary copied from the image `homeserver/Dockerfile` pins, by the
  same digest (`TaskImageTest` holds the two one), set as `MATRIX_TEST_TUWUNEL`. No container starts inside a task.
- **What the task's suite runs:** every unit test and every integration test against the homeserver's binary;
  `LoopbackOwnerIT` skips by its own assumption, since it needs another account's listener in a container. It needs
  no task: it runs in the forge's Build, where the homeserver runs from its image. `PackagesIT` runs in the task
  (dpkg and `rpm` in the image).
- **Snapshots come from the central source; the host's registry holds only what was built there and has not yet gone
  through the central forge.** This repository reads only porelo-parent and the release tools (`porelo-release`,
  `porelo-machines`) and deploys no Maven artifact, so the trial changes no snapshot another repository builds
  against.
- **The room during the move:** the development machine's homeserver carries the project's room, reached from the
  host over one `ssh -R` to one socket, [MX76](README.md#mx76). The agent in the task changes this repository but never
  restarts or updates that homeserver: what it builds is tested in its own task and the forge's Build, and reaches the
  development machine only through the gathered push and a deliberate install there.
- **What the task's context gives the agent in place of `.AGENTS.md`**, which is not committed: its name
  `Agent Matrix`, its attribution `Assisted-by: Claude Code, <model>` and no other trailer, that its upstream is the
  host's forge and the central forge only through the gathered push, the room in place of the channel, and the
  operator's language and time zone. What stays only in `.AGENTS.md`: the VM accounts, the hand-over directory, the
  room tool's scripts and the test vault, none of which a task reaches. What is missing is said to the coordinator
  before the start.
- **The skills:** the seven this repository names, fetched in the task into Claude Code's skills folder, each under its
  slug, checked against its digest.
- **The reading a hand-over needs:** neither Codex CLI nor Antigravity CLI is in the task. The coordinator reads the
  pull request's diff from outside against the seven skills and posts the findings in the room; the task's agent
  answers each there. A fix amends the task's commit and goes with `--force` to the task's own unmerged branch.
- **The steps, in the move procedure's order:**
  1. *Here, before the host:* a task of this repository on this machine, built from `porelo.yml`, runs
     `./mvnw -B -s settings.xml -Pnative verify` and `-Pdocuments test` green, and its skipped tests are named. This is
     the image's first build by Porelo.
  2. *The switch:* the coordinator's `project.yml` commit gives the repository the host's forge as upstream; this issue
     is set Implementing on `main` before it, so the forge receives that state.
  3. *The start:* `porelo task start --repository porelo-message-matrix --agent claude` on the host, attended, its
     terminal opened by the operator.
  4. *Inside the task:* the agent reads its context, fetches the skills, makes the change the trial carries, runs the
     documents tests and the full suite in the task, and pushes. The gate opens the pull request; the agent reads the
     Build and the *Shared rules check* through the `forgejo` reader, both green, answers the reading, and sets
     Review.
  5. *The check:* the move's check for this repository, red before the start, green after.
  6. *The way back:* what the forge holds of the task's work is fetched into the checkout here, the coordinator switches
     the upstream back, and the session here resumes.
- **No test goes red first:** nothing that can do harm changes in the code. The move's red-first check is the move's
  check against this repository before the start.

## Acceptance

- The move's check for this repository fails on every line before the start and passes after: the task runs, its last
  push reached the forge as `porelo/<task>`, the `forgejo` reader read its Build, the gate opened the pull request, and
  the task is in the room's `agent-card.json`.
- This issue's file says Review in the commit the operator merges, and that commit's Build and *Shared rules check* on
  the forge are green.
- A message naming `Agent Matrix` in the room wakes the task's agent, and its answer appears in the room.
- After the way back, this checkout holds the task's commit and the session here works on it.
- Seen to fail: the check against the repository before its move.

## Open questions

- **Which change the trial carries.** The Build skips a push that changes only documents, issues or `porelo.yml`, so a
  document correction, as other moves carry, shows the *Shared rules check* green but starts no Build on the forge.
  Either the trial carries [MX80](README.md#mx80)'s last step, built and tested here, whose first Build on a forge is
  then the one in the task (recommended: it is real work the Build reads, and the forge is where it must be shown), or
  a small change to code or to `settings.xml` that this issue names before the start.

## To be checked

- On the host, before the start: the forge holds this repository with `main` protected, and its Build resolves the
  parent and the release tools with the registry empty.
- That the task's egress reaches what the build and the agent fetch: Maven Central, Sonatype's snapshots, GitHub's
  release downloads (GraalVM), `ghcr.io` for the homeserver's image when the image is built, and `fuinorg.jfrog.io`
  for the skills.
- That the task's context gives what the plan names in place of `.AGENTS.md`, the attribution above all.
- That a native build fits in the task's memory limit: it took about 4 GB on the Fedora VM.
- How a moved agent's task sees the local agents' lines in the room: local agents have names no task has and are
  listed as external ([MX76](README.md#mx76)); that the task's agent is woken only by its own name is shown in step 4.
