# MX16 — The Code Read Against Its Skills

**Status:** Analyzed.
**Size:** M - about 3,300 lines of Java under `src/main`, 4,200 under `src/test`, the pom, three workflows
and the homeserver's unit, read once against seven skills; each finding is fixed with its own test, and any
fix to what ships runs the acceptance on the VM again.

**What must be true.** All of this repository's code has been read against the seven skills its
`AGENTS.md` names, by the versions recorded below, and every finding is either fixed with a test seen to
fail first or declined with its reason written here. The repository began after the round that read the
other repositories' code, so it was never read.

## Plan

- **The skills, by version and SHA-256 of the zip**, as the agent-skills repository gave them on
  2026-10-14; the reading uses these, or records the newer one it used:

  | Skill | Version | SHA-256 |
  |---|---|---|
  | `graal` | 2026.910.3 | `b24ec6272fb13490e32977c21d4543869a7e85ed474fdc0ff34022fbddd88bf6` |
  | `java-code-review` | 2026.906.3 | `802f86339442ed9fa9d5bca14b6d16e1f6420ae92a047b18bba11239e32c698b` |
  | `test-quality` | 2026.906.3 | `9c5d23ad7a6d9c6f5c3679f3cc44a7fcc2fb3cc1c0f0d0ba4a2c0f7812428bad` |
  | `security-audit` | 2026.906.3 | `a46fd1be07bd539b955291537dea0f24f65a2f8b15d40d50a6a9098bbd992747` |
  | `concurrency-review` | 2026.906.3 | `3a0aab612f25483a3fe777eb18fbcc434e936b19298708bf88beff8b22d79e56` |
  | `clean-code` | 2026.906.3 | `5cf777a24630298b4b801c934f599722978c21aeac53d61683243908fc041c7b` |
  | `solid-principles` | 2026.906.3 | `bd47cdab9126dde76cf1703472167a1c88c85306a4e0263a8f2db4c462740cf5` |

- **What is read against what:**
  - all seven against `src/main/java` (one package, 23 classes);
  - `test-quality`, `java-code-review` and `clean-code` against `src/test/java`, the stand-ins
    (`FakeHomeserver`, `Tuwunel`, `Transport`) and the tests tagged `documents` included;
  - `graal` against the `native` profile in `pom.xml`: `-march=x86-64`, reachability of
    Jackson, and what `CpuBaselineIT` checks;
  - `security-audit` against what crosses a trust boundary: `Trust` (the self-signed and CA-file trust
    managers), `MatrixClient` (redirects refused, TLS), `AdminRoom` and `Accounts` (commands to the admin
    room, passwords), `HomeserverToken`, `Poll` and `Person` (bytes from the room written into the inbound
    directory, `writeAtomically`), the workflows' permissions, `homeserver/porelo-matrix-homeserver.service`
    and the `deb` control files;
  - `concurrency-review` against what runs at once: two `poll` or `send` of one project started together
    (the state file, the inbound files, the atomic moves), the interrupt handling in `AdminRoom` and
    `MatrixClient`, and `LocalHomeserver` against a container still starting.
- **Steps:** load the skills into the harness and confirm it lists them; read; write each finding down
  here first; for each, a test that fails on today's `main`, then the fix, or the reason it is declined.
- **Red first:** each finding's own test, seen to fail on the commit before its fix.

## Acceptance

- The hand-over names each skill with the version and SHA-256 it was read with.
- Every finding is listed here with its test and fix, or declined with the reason; a finding without a
  failing test stays a guess and is said as one.
- The whole workflow green on the VM on the commit handed over: unit, documents, acceptance.

## To be checked

- Whether the harness loads the skills from its own skills directory or must read each `SKILL.md` from
  outside it; the reading starts only once one of the two is confirmed.
