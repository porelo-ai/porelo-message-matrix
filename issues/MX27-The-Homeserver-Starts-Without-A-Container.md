# MX27 — The Homeserver Starts Without A Container

**Status:** Review; this repository's part of porelo-project's
[PJ49](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj49), which mends a test first where it
is cheap and tags only what remains.
**Size:** S - one test helper, the draft image's two lines and one test; no contract another repository reads.

**What must be true.** The integration tests run inside a Porelo task: where the image names Tuwunel's own binary,
the tests start it as a process on loopback instead of a container, from the same pinned image, and a developer's
build and the Build stay as they are.

## Why

Run inside its draft `porelo.yml` image as a task runs it, the whole check was green but for 44 of 50 integration
tests: each starts the homeserver with `podman run`, and a container cannot start inside the task's container. As
root inside it fails on the bridge network, `overlay` over overlayfs, a read-only cgroup and mounting `proc`; as the
task's plain user it finds no subordinate id range. The six that passed need no homeserver.

## Plan

Measured on 2026-10-19: the image `homeserver/Dockerfile` pins holds one file of its own, `/usr/bin/tuwunel`, statically
linked. Copied out and run alone, as the task's plain user with `no-new-privileges` inside the draft image, it answered
`/_matrix/client/versions` after about a second, registered an account with the registration token, stopped on
SIGTERM, and started again on the same port with an empty database. It logs that io_uring is refused (errno 38) and
goes on without it.

- `porelo.yml`: `COPY --from=<the image of homeserver/Dockerfile, the same digest> /usr/bin/tuwunel
  /usr/local/bin/tuwunel`, and in `env` `MATRIX_TEST_TUWUNEL: /usr/local/bin/tuwunel`. Not `PORELO_*`: porelo's
  [B192](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b192) refuses the names Porelo sets.
- `Tuwunel` (test code): when `MATRIX_TEST_TUWUNEL` names an executable, `start` runs it as a process with the
  settings the container gets today (`TUWUNEL_*`, loopback, a free port taken first, the database under the test's
  own `rwx------` directory, its output into a file there), and waits for it as today. `reset` stops it, empties the
  database and starts it on the same port; `close` stops it and removes the directory. Otherwise podman, unchanged,
  so a developer's build, the VM run and the Build keep the container.
- A test that the digest in `porelo.yml`'s `COPY --from` is the one `homeserver/Dockerfile` pins, so a move of the
  homeserver by hand cannot leave the task testing another one. Seen red with the two digests apart.
- Red first: the whole check inside the draft image, as a task runs it, gives today's 44 errors; after it, none.

## Acceptance

- Inside the draft image as a task runs it (plain uid, `no-new-privileges`, no devices): all 50 integration tests
  green, with the process.
- On the host and on the VM, with nothing named: all of them green with the container, as before.
- The digest test seen red with the digests apart.
- Met: inside the draft image as a task runs it, with `LANG=C.UTF-8`, all 54 integration tests green with the
  binary (53 with no locale: `LifecycleIT.nickname`'s argument needs a UTF-8 locale, which every task image gets);
  on the host, on the VM and in Build 39257077295 all of them green with the container; `TaskImageTest` seen red
  before `porelo.yml` copied the binary and with one character of the digest changed.

## To be checked

- That the reader porelo's
  [B192](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b192) builds takes a `COPY --from` line in `snippet`,
  and that a pinned image is a source the project's `package_sources` names; until then the line stays a draft as
  the file is.
- Whether the registration token may reach the process by its environment, readable in `/proc/<pid>/environ` by the
  same uid only, where the container takes it from a file; or a config file of `rw-------` in the test's directory.
- The free port taken before the start: the moment between can lose it to another process; the start then fails
  and says so, as a container's port clash does.
