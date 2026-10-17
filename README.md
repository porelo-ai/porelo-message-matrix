# porelo-message-matrix

<img src="doc/images/early-bird.svg" width="350" alt="Early bird - work in progress">

> **Early bird - work in progress.** Porelo is not stable yet: until release 1.0.0, its code, commands
> and file formats can change without notice.

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
