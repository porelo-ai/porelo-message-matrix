# MX16 — The Code Read Against Its Skills

**Status:** Backlog; after the reformatting ([MX18](README.md#mx18)), so its diff carries no formatting.
**Size:** L - grown from M with its findings: thirteen fixes and nine tests, several against a real homeserver;
about 3,300 lines of Java under `src/main`, 4,200 under `src/test`, the pom, three workflows
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
- **Steps:** the harness here cannot install into its skills directory, so each `SKILL.md` is read from a
  directory outside the repository, as `AGENTS.md` allows; read; write each finding down
  here first; for each, a test that fails on today's `main`, then the fix, or the reason it is declined.
- **Red first:** each finding's own test, seen to fail on the commit before its fix.
- **No interface another repository reads changes:** not the transport's commands, settings, exit codes or
  `describe`, nor the packages' names and paths. A finding that would change one is not built; it is named in the
  channel.

## Acceptance

- The hand-over names each skill with the version and SHA-256 it was read with.
- Every finding is listed here with its test and fix, or declined with the reason; a finding without a
  failing test stays a guess and is said as one.
- The whole workflow green on the VM on the commit handed over: unit, documents, acceptance.

## Findings

Read on `5f20172` with the seven skills at the versions above, each zip's SHA-256 checked against the table and
the repository's; line numbers are that commit's. Each is a guess until its test is seen red. None is fixed yet:
the fixes come after the reformatting.

**To fix, no interface changes:**

1. **A malformed `matrix.to` link stops the room's poll for good** (`security-audit`, input validation).
   `Person` decodes the link with `URLDecoder.decode`, which throws `IllegalArgumentException` on `%zz`; nothing
   catches it, so `poll` ends with 1 and a stack trace, and since the position is saved after the files, every
   later poll ends the same way. Any member of the room can send it. Red: `Person.deliver` with such an event
   throws today; it is to hand the message over without that id.
2. **The lifecycle verbs skip the loopback rule for the environment's URL** (`java-code-review`, validation;
   `clean-code`, the rule exists twice). `Lifecycle.place` parses `PORELO_MATRIX_HOMESERVER` with a bare `URI`,
   not through `Config.from`, and `--loopback-only` checks only the settings' homeserver. So `enroll`, `join`,
   `retire` and `clear` send the admin token over plain `http` to another host. Red: `enroll` with
   `http://no-such-host.invalid` answers 75 today, not 78 with no request.
3. **Two admin commands at once can take each other's reply** (`concurrency-review`, check-then-act).
   `AdminRoom.reply` takes the first newer message not from the admin, whatever command it answers. A reset
   password's reply can then stay readable in the admin room, or be printed unmasked in a 76. Red: a fake admin
   room that answers another command first. Whether Tuwunel's replies name the command they answer is measured
   first.
4. **`m.direct` is read, changed and written back whole** (`concurrency-review`). A `send --to` and a
   `poll --direct` of one task at once can drop a room from it; `poll` then skips that room without naming it in
   `skipped`, and a second `send` opens a second chat. Red: two `Direct.remember` against a fake that holds both
   reads; and a joined chat missing from `m.direct` named in `skipped`.
5. **`setup` can wait without end** (`java-code-review`, timeouts). `LocalHomeserver.awaitUp` sends without a
   request timeout, so a port that accepts and never answers holds it past its 180 s. Red: a silent socket.
6. **`tls_verify: off` in the settings warns nowhere** (decision: "loudly"). `Main` warns only for the
   environment variable. Red: `setup` with it in the settings, `WARNING` on stderr.
7. **The transport and the homeserver's unit disagree under `XDG_CONFIG_HOME`.** `HomeserverToken` and
   `LocalHomeserver` follow it; the unit reads `%h/.config/...`. Red: the directory the code chooses against the
   one the unit names.
8. **An empty registration-token file is kept for good** (`security-audit`, fail closed). `HomeserverToken`
   creates the file, then writes it; a run stopped between the two leaves an empty 0600 file every later run
   accepts. Red: an empty file is accepted today.
9. **`send` ends with 1 on a display name near 64 KiB** (`java-code-review`). `Send` takes
   `body.substring(head.length())` after `fit` may have cut into the mention's head. Red: `Send.mention` with a
   70,000-character name.
10. **A NUL in `ca_file` ends with 1 instead of a refusal.** `Settings` passes it to `Path.of`. Red: `settings`
    with `"/a\u0000b"`.
11. **`clear` deletes the lock and position of a poll still running** (`concurrency-review`). The poll writes the
    old position back, and the project set up again then ends every poll with 78. Red: `clear` while a fake
    holds the poll's sync. Whether Porelo stops polling before `clear` is asked first.
12. **`setup`'s `reaches` has no port** for a homeserver URL without one (`matrix.example.org`, not
    `matrix.example.org:443`), against `doc/transport.md`. Porelo reads it in one place and strips a port there,
    so both read alike and the fix changes nothing Porelo does. Red: `reaches` of `https://h` is `h:443`.
13. **`actions/checkout` keeps the job's token in `.git/config`** (`security-audit`), in `build.yml` and
    `shared-rules.yml`: nothing sets `persist-credentials: false`, so every later step, and any action it runs,
    can read the token. No step pushes with git. **Fixed in MX18's commit**, since the tools that check the style
    refuse such a checkout too: red was the shared `check-actions` on the workflows before it.

**Tests that cannot fail** (`test-quality`), each shown by a change to the code it stays green on. Under the
shared rule a test guards only what can do harm, so each names its harm; one that guards none is repaired without
a test, or not at all:

14. `clear` forgetting the relay's position: `LifecycleIT` never polls before it. Harm: the project set up again
    ends every poll with 78.
15. No redirect followed (76): no test answers a 3xx; `Redirect.NORMAL` stays green. Harm: the token goes to
    whatever host the homeserver names.
16. Mentions dropped when the event would not fit: no test sends a long line with a mention. Harm: the homeserver
    refuses the event and Porelo the message, for good.
17. A second poll in another process: `PollTest` holds the lock in its own JVM, so the `tryLock` null branch never
    runs. Harm: two polls write the position, and a message is delivered twice.
18. Paging back to the last position: `PollIT` has no older message, so dropping `to=since` stays green. Harm:
    a limited sync delivers the room's whole history again.
19. `LoopbackOnlyTest`: one guard sends to a host the fake never hears, so it reads nothing; another reaches
    `127.0.0.1:8008`, outside the test. Harm: an offline project's token leaves the machine unnoticed.
20. `DirectIT` checks the task's own `m.direct`, not what the person received. Harm: a person gets a second chat,
    and what they write in the first is dropped.
21. `read` of a person's file name and receipts in a thread: untested. Harm: a person's message is never marked
    read, or a reply in a thread is taken as read. The display name's fallback and how much `cut` keeps only
    read wrong: no test.
22. `PollIT.noPartialFiles` stays green without the atomic move. Harm: Porelo reads a half-written message.
    Repaired; and two `PollIT` tests that time their events by sleeps are made to wait on the event.
23. **Repaired without a test of its own**, as they harm no user: `DocumentTestsTaggedTest` scans `*Test.java`
    only, not the integration tests; `Tuwunel.start` leaves its container and token behind when it fails, the
    `@AfterEach` then hides the cause behind a `NullPointerException`, and `reset` binds the same port again after
    another may have taken it.

**Style** (`clean-code`, `solid-principles`), fixed where the code is touched anyway: the URL and XDG rules
written two and three times; `Lifecycle` holding five verbs; `Config` and `Settings` depending on `Lifecycle`;
`Poll.once`'s eleven parameters; four Javadoc comments wrong or misplaced; inline fully qualified names;
assertions without `.as(...)`.

**Not built here, as they change what another repository reads; issues of their own:**

- `clear --project` deactivating a person who is in another project too: [MX19](README.md#mx19).
- Nothing proving the loopback port is this account's own homeserver: [MX20](README.md#mx20).

**Declined:** one transaction id for the same message to two targets, since it hashes the message and its
signature and not the target. Porelo never sends one message to two targets: each queued message has one
destination, and a deferred one is sent again to the same, where one id is what keeps a retry from posting twice.

**Sound:** `Trust` keeps the host-name check; `MatrixClient` follows no redirect, keeps tokens out of errors and
ends an interrupt with 75; `AdminRoom`'s argument pattern stops a second command; passwords from `SecureRandom`;
`Poll`'s files moved atomically, named by a UUID, the position saved last under a lock; `Reference`'s patterns;
`Lifecycle.ours`; the unit publishes on loopback only with federation off; the packages' modes; the workflows'
permissions and pinned actions. **`graal`:** `-march=x86-64` is the one build argument and right; no reachability
metadata is needed, as Jackson is used as a tree only; `CpuBaselineIT` fails closed.

