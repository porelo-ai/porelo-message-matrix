# porelo-message-matrix

<img src="doc/images/crazy-chicken.svg" width="350" alt="Crazy chicken - just hatched">

> **Crazy chicken - just hatched.** Interfaces, formats and even the purpose may change any day. Use it only to try ideas, never to build on.

The Matrix transport for Porelo: the tasks of a project talk in one Matrix room, and a person can read and
write in it with any Matrix client. Porelo runs the transport itself; you install it and name it in a
project. It decides nothing about what may pass and never parses a message - that is the filter's, in
`porelo-message-sluice`.

**How to install and use it: [the Matrix chapter of Porelo's documentation](https://porelo-ai.github.io/matrix/).**

## Install

From the same package repository as Porelo:

    sudo apt-get install porelo-message-transport-matrix porelo-matrix-homeserver

## Build

Java 25 (GraalVM for the native executable) and podman; `mvnw` brings the pinned Maven.

    ./mvnw -s settings.xml verify            # unit and integration tests, against the homeserver in podman
    ./mvnw -s settings.xml -Pnative verify   # also the executable, its tests, and the .deb and .rpm packages

`-Pnative` also needs `zlib1g-dev` and `rpmbuild`.

## Contributing

How to contribute is in [CONTRIBUTING.md](CONTRIBUTING.md); a vulnerability is reported as [SECURITY.md](SECURITY.md)
says.
