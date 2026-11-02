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

Mutation testing runs by hand, never in the default build or CI, and only on a VM: a mutant is real code, and the tests
call real `podman` and `systemctl`, so it could hit a homeserver of the same account. Tests that start a child JVM need
the class path Surefire would name, and those children run unmutated code:

    ./mvnw -s settings.xml test-compile dependency:build-classpath -Dmdep.outputFile=target/deps.cp
    ./mvnw -s settings.xml -Ppit -Dpit.test.class.path="$PWD/target/test-classes:$PWD/target/classes:$(cat target/deps.cp)" \
        test-compile org.pitest:pitest-maven:mutationCoverage

`-Dpit.classes=<classes>` limits it to a change's own classes. The report lands in `target/pit-reports/`. The
integration tests do not run under it, so what only they reach counts as not covered.

## Contributing

How to contribute is in [CONTRIBUTING.md](CONTRIBUTING.md); a vulnerability is reported as [SECURITY.md](SECURITY.md)
says.
