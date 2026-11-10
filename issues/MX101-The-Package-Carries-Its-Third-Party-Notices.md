# MX101 — The Package Carries Its Third-Party Notices

```json
{
  "$schema": "issue-1.json",
  "id": "MX101",
  "title": "The Package Carries Its Third-Party Notices",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 6,
  "blockedBy": [],
  "covers": "The transport's .deb and .rpm carry THIRD-PARTY-NOTICES with the full license and notice texts of everything compiled into its native image, from porelo-buildtools' generator"
}
```

**Why this size.** the generator's call in the build, two package entries and the copyright text, and the package
test's check; the generator itself is porelo-buildtools'.
**How it stands.** Filed on the coordinator's order of 2026-11-10 (the operator's word, 12:12Z): one generator for all.
It waits on that generator being on porelo-buildtools' main; nothing is built before.

**What must be true.** Whoever installs `porelo-message-matrix` finds the license and notice texts of every
third-party part compiled into the transport: `/usr/share/doc/porelo-message-matrix/THIRD-PARTY-NOTICES` in the
.deb, named in its `copyright`, and the same file as `%license` in the .rpm.

## Why

The transport ships as one native image. Compiled into it are Jackson (databind, core, annotations; jackson-core
embeds FastDoubleParser's notices), jspecify and the GraalVM runtime. Today the packages carry only this
repository's own `LICENSE` as their copyright, so the texts those licenses ask to be passed on are missing.

The homeserver package carries only its systemd unit; the homeserver's image is fetched at run time, nothing of it is
compiled in, so it needs no notices of its own.

## Plan

- **Generate**: porelo-buildtools' generator runs in the build over the runtime classpath of the native image and
  writes `target/THIRD-PARTY-NOTICES`.
- **Ship**: the jdeb data set and the rpm mapping take the file; the .deb's `copyright` names it.
- **The test that goes red first:** `PackagesIT` asserts the file is in both packages, holds Jackson's and the GraalVM
  runtime's license texts, and that the .deb's `copyright` names it.
