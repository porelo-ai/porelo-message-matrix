# MX101 — The Package Carries Its Third-Party Notices

```json
{
  "$schema": "issue-1.json",
  "id": "MX101",
  "title": "The Package Carries Its Third-Party Notices",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 6,
  "blockedBy": [],
  "covers": "The transport's .deb and .rpm carry THIRD-PARTY-NOTICES with the full license and notice texts of everything compiled into its native image, from porelo-buildtools' generator"
}
```

**Why this size.** the generator's call in the build, two package entries and the copyright text, and the package
test's check; the generator itself is porelo-buildtools'.
**How it stands.** Review (2026-11-10): built and green in the package test on a native build here; each of the three
breaks of the packaging (the .deb's file, its copyright, the .rpm's file) turned it red. Filed on the coordinator's order of 2026-11-10 (the operator's word, 12:12Z):
one generator for all (porelo-buildtools BT63, `porelo-release third-party-notices`).

**What must be true.** Whoever installs `porelo-message-matrix` finds the license and notice texts of every
third-party part compiled into the transport: `/usr/share/doc/porelo-message-transport-matrix/THIRD-PARTY-NOTICES` in
the .deb, named in its `copyright`, and the same file under `/usr/share/licenses/porelo-message-transport-matrix/` in
the .rpm.

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

## Plan as built

- **Generate**: in the native profile, after the CycloneDX bill (`target/sbom.cdx.json`), `maven-dependency-plugin`
  writes the runtime classpath to `target/classpath.txt` and `exec-maven-plugin` runs `porelo-release
  third-party-notices` over both and the JDK the image is built with, into
  `target/THIRD-PARTY-NOTICES`. The same call as porelo-message-sluice's, without its `--own`: this repository is one
  module, no jar of its own is on the classpath (the generator says "0 of the project's own left out" either way).
- **Ship, .deb**: the file goes into `/usr/share/doc/porelo-message-transport-matrix/` (`missingSrc` fail). The
  `copyright` there was this repository's `LICENSE`; it is now `src/deb/copyright` in Debian's copyright format, whose
  `Comment` names the notices and whose license points to `/usr/share/common-licenses/GPL-3`, as Sluice's does.
- **Ship, .rpm**: the file goes into `/usr/share/licenses/porelo-message-transport-matrix/`. rpm-maven-plugin 2.3.0 can
  mark a file only `%doc` or `%config`, not `%license`; as a plain file it is still installed with excluded
  documentation, which is what `%license` is for. Marking it `%license` needs another rpm plugin; not done here.
- **Test**: `PackagesIT.packagesShipTheThirdPartyNotices` reads the generated file for a section of every runtime jar,
  the GraalVM runtime and FastDoubleParser's notice, then finds it in both packages,
  the .deb's identical to it and its `copyright` naming it.
