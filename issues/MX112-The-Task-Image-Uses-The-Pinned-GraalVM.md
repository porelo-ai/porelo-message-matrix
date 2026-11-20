# MX112 — The Task Image Uses The Pinned GraalVM

```json
{
  "$schema": "issue-1.json",
  "id": "MX112",
  "title": "The Task Image Uses The Pinned GraalVM",
  "state": "Review",
  "size": "XS",
  "priority": "Medium",
  "rank": 6,
  "blockedBy": [],
  "covers": "`porelo.yml`'s task image and the busy-room step's `JAVA_HOME` fallback name GraalVM 25.5.5.0.3, and the task image the Ubuntu 24.04 digest, that the shared build tools pin, as the CI build already does"
}
```

**Why this size.** Three lines of version, URL and digest in two files, with no contract change.

**What must be true.** A build in a Porelo task and the busy-room rehearsal step run on the same GraalVM as the CI
build: 25.5.5.0.3, whose Linux x64 archive has the SHA-256
`c6438dee6c3ade2220d40b7ccdcad24b512d30e233e61bb4fc70519522de34ef`, on Ubuntu 24.04 by the digest the build tools pin,
`sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852`.

## Plan

- `porelo.yml`: the task image's download names
  `graal-25.5.5.0.3/graalvm-community-jdk-25i5-25.0.5.0.3_linux-x64_bin.tar.gz` and checks the digest above, in place
  of 25.4.4.1.1 and its digest; its `from` names the Ubuntu digest above, which moved in the build tools too.
- `build/rehearse-steps/busy-room`: the `JAVA_HOME` fallback names the development machine's shared copy of 25.5.5.0.3.
- Red first: a search of the tracked files for `25.4.4` finds both today.

## Acceptance

- A search of the tracked files outside `issues/` for `25.4.4` and the old Ubuntu digest finds nothing.
- The task image builds, its download passing the digest check, and `java -version` in it reports 25.0.5.0.3.
- The busy-room step, started without `JAVA_HOME`, builds with the new version.
