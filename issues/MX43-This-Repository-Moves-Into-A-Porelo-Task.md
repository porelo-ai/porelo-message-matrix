# MX43 — This Repository Moves Into A Porelo Task

**Status:** Idea.

**What must be true.** porelo-message-matrix is worked by its own agent inside a Porelo task of the project `porelo` on
the rented host: the agent takes this issue from start to Review in the task, its pushes reach the host's forge, the
forge's build is green, and it is reached in the project's room under this repository's name.

## The shape

- **What moves:** this repository alone, with its agent; the issues, the documentation and the code.
- **The task's image:** the pinned GraalVM and Maven of the build workflow, and what the integration tests need to
  start the homeserver.
- **The forge's build:** the steps of today's Build workflow on the host's forge, the packages and their install legs
  included.
- **The room's name:** `Agent Matrix`, from the nickname `Matrix` in porelo-project's `project.yml`, as Porelo names an agent
  after its nickname.
- **The way back:** as the project's procedure for an agent's move lays out; the task's open work fetched into the local
  checkout first.
- **The room during the move:** the development machine opens `ssh -R` to the host, to one socket only, and
  porelo-project's signed `project.yml` names that socket as `homeserver: unix:<path>` with the room; the registration
  token is lent from the person's vault. The homeserver stays on the development machine until the last agent has
  moved, since the host is rented for a few hours at a time and work there goes on without it. This path is not built
  yet: [MX76](README.md#mx76). After the last move the project goes back to `{}`, with a new room on the host's own
  homeserver; that machine's room and its homeserver data for the project are cleared then with
  `clear --project porelo` and `clear`.

## To be checked

- Whether a task can run podman. If not, the integration tests start the homeserver's binary (the tests already take
  one named in `MATRIX_TEST_TUWUNEL`), taken from the pinned image by digest.
- Which of the integration tests need podman itself (the packages' install legs, the unit's own start) and where
  they run instead: the forge's machines by kind, or the local VM until then.
- That the agent working on the homeserver is never the one that restarts the homeserver its own room runs on during
  the move: the development machine's homeserver carries the room until the last agent has moved.
- What of `.AGENTS.md`'s part for this repository moves into the task's settings, and what stays only here.
- How a moved agent's task takes the local agents' lines: open in [MX76](README.md#mx76).
