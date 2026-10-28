# MX43 — This Repository Moves Into A Porelo Task

**Status:** Idea.

**What must be true.** porelo-message-matrix is worked by its own agent inside a Porelo task of the project `porelo` on
the rented host: the agent takes this issue from start to Review in the task, its pushes reach the host's forge, the
forge's build is green, and it is reached in the project's room under this repository's name.

From [PJ59](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj59), the move of this repository
in its order.

## The shape

- **What moves:** this repository alone, with its agent; the issues, the documentation and the code.
- **The task's image:** the pinned GraalVM and Maven of the build workflow, and what the integration tests need to
  start the homeserver.
- **The forge's build:** the steps of today's Build workflow on the host's forge, the packages and their install legs
  included.
- **The room's name:** `Agent Matrix`, from the nickname `Matrix` in porelo-project's `project.yml`, as porelo
  [B298](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b298) names an agent.
- **The way back:** as the procedure of [PJ59](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj59) gives it for every agent; the task's open work fetched into the local
  checkout first.
- **The room during the move:** porelo-project's signed `project.yml` names
  `homeserver: https://matrix.porelo.internal:<port>` with the room, the CA as `ca_certificate` and
  `registration_token_file: ~/.config/porelo/matrix/host-registration-token`. This machine resolves the name to
  loopback and keeps its own homeserver's path, and the host resolves it to this machine's VPN address. After the last
  move the project goes back to `{}`, with a new room on the host's own homeserver; this machine's room and its
  homeserver data for the project are cleared then with `clear --project porelo` and `clear`.

## To be checked

- Whether a task can run podman. If not, the integration tests start the homeserver's binary (the tests already take
  one named in `MATRIX_TEST_TUWUNEL`), taken from the pinned image by digest.
- Which of the integration tests need podman itself (the packages' install legs, the unit's own start) and where
  they run instead: the forge's machines by kind, or the local VM until then.
- That the agent working on the homeserver is never the one that restarts the homeserver its own room runs on during
  the move: [MX42](README.md#mx42)'s published homeserver on this machine carries the room until the last agent has
  moved.
- What of `.AGENTS.md`'s part for this repository moves into the task's settings, and what stays only here.
- How a moved agent's task takes the local agents' lines: as a person's words, and whether the filter holds them.
