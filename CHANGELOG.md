# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- A project on a homeserver elsewhere names its registration token's file as `registration_token_file`, so it
  can be set up through Porelo, which hands a transport no variable of the person's choosing; `settings` checks
  it while the project file is edited.

### Security

- The running transport is not dumpable, so no other process of the account reads its tokens from `/proc`
  while it runs; a machine that refuses that gets 78.
- The transport reads JSON with jackson 2.22.3, which fixes the denial-of-service and deserialization advisories
  published against 2.22.1 at the end of September 2026.

### Changed

- The code follows the shared style of every Porelo repository: one `.editorconfig`, and `google-java-format`
  in its AOSP style checked by the build, which refuses code that does not match it. The build takes
  `porelo-parent` 0.2.1 and the shared tools 0.5.1, and its checkouts keep no token for later steps.
- `setup` names the scheme's port in `reaches` when the homeserver's URL names none, as the contract says.
- The homeserver package runs Tuwunel 1.9.4, pinned by its digest.
- The homeserver's unit reads its port and registration token under `$XDG_CONFIG_HOME` when it is set, where
  the transport writes them.

### Fixed

- A person's message with a mention link that is no valid escape no longer stops every later poll of the room.
- `enroll`, `join`, `retire` and `clear` refuse the homeserver kept from `setup` in plain http on another host
  (78), and under `--loopback-only` one that is not loopback, before any request.
- An admin command takes as its reply only the one that answers it, so a password reset during another
  lifecycle verb no longer leaves its reply unredacted.
- A direct chat written into `m.direct` while another writer wrote it is written again, so its messages are read.
- `setup`'s wait for the local homeserver ends at its limit on a port that takes the connection and never
  answers.
- `tls_verify: off` in the project's settings is warned of on stderr at every start, as the environment's is.
- An empty registration-token file, left by a start stopped half-way, is made anew, and the token is written
  whole before it takes its name.
- `send` with a mention whose display name fills the event is sent without its pill, instead of ending with 1.
- A `ca_file` that is no path is refused with 78, instead of ending with 1.
- `clear --project` waits for the relay's running poll before it forgets its position, so the poll cannot write
  it back and the project set up again polls; past 45 s it keeps the position and says why.

## [0.5.0] - 2026-10-17

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
