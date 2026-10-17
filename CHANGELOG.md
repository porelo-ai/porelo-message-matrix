# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed

- The transport is Porelo's: the packages `porelo-message-transport-matrix` and `porelo-matrix-homeserver`, the
  program under `/usr/libexec/porelo/transports/`, the settings `PORELO_MATRIX_*`, its directories under `porelo/`
  and the accounts and room `@porelo`, `@porelo-relay-<project>` and `#porelo-<project>`. It is installed afresh:
  nothing a machine kept for the earlier transport is read or kept, and a homeserver already provisioned by it is
  set up anew.
- Every run on GitHub is titled with its workflow's name, the branch or tag, and the commit's message; the
  workflow for the shared rules is called the Shared rules check.
- The build takes the snapshot of `porelo-parent`, and with it the snapshot of the shared build tools; a
  release of this repository waits until both are released and pinned.
- A waiting `poll` goes on waiting through an answer that brings nothing to hand over, such as a read receipt,
  so a message that comes just after one is handed over at once, not at Porelo's next look seconds later.

## [0.4.1] - 2026-10-08

### Changed

- Every change is checked with `sokar-release`: no page or source cites an issue, and every page
  under `doc/` is in the site's navigation exactly once. A change to documents alone runs only these
  checks and the tests that read a document, not the build.
- A release refuses to build when anything it is built with is a snapshot: a dependency, the BOM's
  managed versions, a plugin or a plugin's dependency.
- Every change is checked for a `README.md` beside the `pom.xml`, saying what the transport is and is not.
- The build takes `sokar-parent` as its parent, which holds what every Sokar repository shares; it publishes
  nothing to Maven Central.

## [0.4.0] - 2026-10-05

Initial public version.
