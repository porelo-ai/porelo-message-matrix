---
name: matrix-build
description: porelo-message-matrix's build: the documents tests and checks, the files that are not Java, moving the homeserver image's pin. Use before a test run, a hand-over, moving the homeserver image or changing a file that is not Java.
---

# matrix-build

## Work in this repository

- **The tests tagged `documents` run alone with `./mvnw -B -s settings.xml -Pdocuments test`**;
  `DocumentTestsTaggedTest` fails when a test reads a document without the tag. None reads one today: the
  documents are checked with `porelo-release`'s `check-citations`, `check-doc-site` and `check-readmes` on the
  host before every hand-over, and by the `shared-rules` workflow once a day; the build skips a change to
  documents alone.
- **Files that cannot be Java, and why:** `mvnw`, the Maven wrapper; `build/rehearse-busy-room.sh`, shell, since it only opens an ssh forward around the Java rehearsal and closes it
  again; `build/rehearse-steps/busy-room`, shell, the rented-host run's step that calls it, kept here and copied into
  porelo-host as it is.
- **The homeserver image's pin is moved by hand.** Dependabot moves only the actions; the agent moves the digest
  in the `FROM` line of `homeserver/Dockerfile`, with its changelog line and a VM run. `ghcr.io` gives an image
  no publication date, so the agent reads its `org.opencontainers.image.created` and takes nothing younger
  than three days.
