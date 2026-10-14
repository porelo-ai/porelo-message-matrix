# MX17 — The Transport Is Renamed Porelo

**Status:** Estimated, at the operator's word; from `sokar-project` [PJ41](https://github.com/sokar-ai/sokar-project/blob/main/issues/README.md#pj41).
**Size:** S - one module: 532 occurrences in 75 of 87 tracked files and 59 paths, done by a script; what makes it
more than XS is the packages and paths that ship, so the acceptance runs on the VM against a renamed Porelo.

**What must be true.** The Matrix transport, its homeserver package, its packages and paths, its settings and its
documentation are named Porelo; nothing a person installs, types or reads from this repository still says Sokar,
except where it says what the old name was. It is installed afresh: nothing of a machine that ran Sokar's transport
is read or kept.

## Plan

- **What carries the name**, measured on `234ee88`:
  - **Artifact and packages:** `org.fuin.sokar:sokar-message-matrix` on `sokar-parent`; the Java package
    `org.fuin.sokar.message.matrix` (24 main, 34 test files under that path); the `.deb`/`.rpm`
    `sokar-message-transport-matrix` (provides `sokar-transport`, requires `sokar` and
    `sokar-message-sluice-filter`) and `sokar-matrix-homeserver` (provides `sokar-homeserver`); the build's
    properties `sokar.snapshot.run`/`.suffix`; the scm and site URLs under `sokar-ai`.
  - **Contracts others read:** the executable `/usr/libexec/sokar/transports/sokar-message-transport-matrix`,
    which `sokar`'s `TransportDirectory` finds and `sokar-frontend`'s setup installs; the settings
    `SOKAR_MATRIX_HOMESERVER`, `_ACCESS_TOKEN`, `_ADMIN_TOKEN`, `_REGISTRATION_TOKEN`, `_CA_FILE`, `_TLS_VERIFY`,
    `_PORT`, which `sokar`'s `HelperEnvironment` passes and `sokar-frontend` writes; the user unit
    `sokar-matrix-homeserver.service` and its container. Both other sides take the plain replacement in the same
    window, nothing kept under the old name: `sokar` passes `PORELO_MATRIX_*` and finds
    `/usr/libexec/porelo/transports`, `sokar-frontend` writes the same variables and installs the renamed packages.
  - **On the wire and in the homeserver:** the event content keys `org.fuin.sokar.message` and
    `org.fuin.sokar.signature`; the administrator `@sokar`, the relay `@sokar-relay-<project>`, and the
    transaction ids `sokar-admin-…`, `sokar-redact-…`.
  - **Paths:** `~/.config/sokar/matrix/` (the local homeserver and its registration token) and
    `~/.local/state/sokar/transports/matrix/` (the poll state), each also under its `XDG_*` variable.
- **Exceptions the script puts back:** the released entries of `CHANGELOG.md` (`0.4.1`, `0.4.0`); the Shared Area in
  `AGENTS.md`, which is taken whole from `sokar-project` in the window, not renamed here; dates, hashes and the
  issue numbers of other repositories quoted in `issues/`.
- **What replacement alone does not do:**
  - The names built from parts are all `"sokar-" + …` (relay account, transaction ids, the homeserver's name) and
    are replaced with the rest; none splits the word. `Sokar` inside a test name (`unsignedSokarMessageHasNoSig`)
    is replaced the same way.
  - **A homeserver Sokar already provisioned is not taken over.** `setup` registers `@porelo` as the first account,
    which Tuwunel makes its administrator; on a homeserver where `@sokar` already holds that, `setup` is refused
    (78) as for any homeserver it does not administer. With no migration that is what holds; the docs say a
    homeserver is set up afresh, and the local one gets a new directory and container anyway.
  - **Lengths:** `porelo` is one character longer per occurrence. Matrix ids allow 255; the relay
    `@porelo-relay-<project>:<server>` stays well below; a test that compares an id or a line is read in the diff,
    not regenerated.
  - **The Debian pool's letter:** the Build uploads to and reads back `pool/main/s/<package>/` (three places in
    `build.yml`); a package named `porelo-…` lies under `pool/main/p/`, which no replacement of the word makes.
  - No image, binary or recorded fixture carries the name.
- **Published before it builds:** `porelo-parent` `0.2.0` and the tools `0.5.0` (with `porelo-release` and
  `porelo-machines`, which the checks and the build run); for the acceptance, Porelo's snapshot and the renamed
  `porelo-message-sluice-filter` package in Artifactory's renamed repositories.
- **Steps:** red first; run the script, `git mv` of the package directories and the homeserver unit, the
  replacement, the exceptions put back; read the whole diff; the changelog's `0.5.0` entry says the transport is
  now Porelo's.
- **Red first:** a test tagged `documents` that fails while any tracked file outside the exceptions says `sokar`
  in any case, seen red on today's tree before the script runs, and kept.

## Acceptance

- The test above red before the script, green after; the shared checks and the tests tagged `documents` green on
  the commit itself.
- The whole build green, the native executable included, and the acceptance on the VM against Porelo's snapshot:
  the transport installed from its renamed package, `setup` on a fresh local homeserver, a message sent, polled and
  read, and `retire`.
- No `sokar` path, setting or account is created on the VM by the transport, checked after the acceptance.

## To be checked

- Whether this repository's version moves to `0.5.0-SNAPSHOT` with Porelo and the tools, or keeps its own line.
