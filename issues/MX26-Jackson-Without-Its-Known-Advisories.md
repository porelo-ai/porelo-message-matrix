# MX26 — Jackson Without Its Known Advisories

**Status:** Review.
**Size:** XS - one version in the pom and a changelog line; the native executable's tests and a VM run prove it.

**What must be true.** The transport that ships parses JSON with no jackson version that has a published advisory
against it: jackson-core and jackson-databind move from 2.22.1 to 2.22.3, which fixes all seven.

## Plan

- `pom.xml`: `jackson.version` 2.22.1 to 2.22.3, from Central on 2026-09-21. It has stood four weeks; 2.22.4, out
  on 2026-10-17, is too young by the shared tools' rule of the newest release that has stood.
- The advisories against 2.22.1, read in OSV: jackson-databind GHSA-cxp5-3px4-pw24, GHSA-wv8q-qhhj-9h54 and
  GHSA-q4xh-88c3-wmh7 (high), GHSA-gx83-3vf8-gh7j and GHSA-wjgm-6hv5-3cvf (moderate); jackson-core
  GHSA-7hhh-6rmp-j9qf and GHSA-p6pp-m3f8-5c89 (high).
- Reach, as read: the transport only calls `readTree` on a String or a response body (`MatrixClient`,
  `Settings`, `SettingsCheck`); it binds no types, uses no polymorphic typing and parses no `DataInput`. So the
  flaws look out of reach, but the JSON comes from a homeserver that can be one elsewhere, and the fix costs a
  version.
- No test of its own: what could go wrong is a parse that changes, which every test that reads a homeserver's
  answer or the settings already covers, against the native executable too.

## Acceptance

- OSV lists no advisory against the jackson the build resolves.
- The whole build with `-Pnative,dist` is green, and a VM run of the native executable's integration tests too.
- Met: green on the host with `-Pnative,dist` and on the VM with `-Pnative`; the bill of the published
  `0.5.1~snapshot.68` names jackson-core and jackson-databind 2.22.3, which OSV knows no advisory against.
