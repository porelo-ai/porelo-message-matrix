# MX27 — The Homeserver Starts Without A Container

**Status:** Accepted; this repository's part of porelo-project's
[PJ49](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj49), which mends a test first where it
is cheap and tags only what remains.

**What must be true.** The integration tests run inside a Porelo task: where the image names Tuwunel's own binary,
the tests start it as a process on loopback instead of a container, from the same pinned image, and a developer's
build and the Build stay as they are.

## Why

Run inside its draft `porelo.yml` image as a task runs it, the whole check was green but for 44 of 50 integration
tests: each starts the homeserver with `podman run`, and a container cannot start inside the task's container. As
root inside it fails on the bridge network, `overlay` over overlayfs, a read-only cgroup and mounting `proc`; as the
task's plain user it finds no subordinate id range. The six that passed need no homeserver.

## The shape proposed

- `porelo.yml` copies the binary out of the image `homeserver/Dockerfile` pins, by the same digest, and names it in
  `env`.
- `Tuwunel` starts that binary when it is named, with the settings it gives the container today, its database in the
  test's own directory; podman stays the way otherwise.
- Whatever cannot run that way carries the `needs-nested-container` tag [PJ49](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj49) proposes.

## To be checked

- Where the binary lies in the image, and whether it runs alone on Ubuntu 24.04, with its own config and database
  directory.
- Whether the tests that stop, restart or reset the homeserver (the reset and poll positions) work against a process
  as against a container.
