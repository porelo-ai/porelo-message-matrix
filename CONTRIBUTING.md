# Contributing

Changes to this repository follow the Porelo project's guide for contributors: how Porelo is worked on, and the
rules a change follows, which hold for a person as for an agent. Read it first:
[Contributing to Porelo](https://porelo-ai.github.io/porelo-project/contributing/).

## In this repository

- **Build and test** as the README's [Build](README.md#build) says: Java 25, GraalVM for the native executable, and
  podman, since the integration tests start the pinned Matrix homeserver in a container. `-Pnative` also needs
  `zlib1g-dev` and `rpmbuild`.
- **What the transport is to Porelo** is its contract, [the transport](doc/transport.md): its verbs, settings and
  exit codes. A change to it is made here and in `porelo` together.
- **Why it is built as it is**, accepted risks included, is in [the decisions](doc/decisions.md).
