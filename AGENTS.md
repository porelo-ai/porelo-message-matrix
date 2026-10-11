# Rules for working in this repository

## Shared across the Sokar repositories

> **BEGIN Shared Area** · sha256 `8747535e984a49b5` · changed 2026-10-11T08:32Z

Identical in every repository `project.yml` names but `sokar-parent`, which holds only the parent pom
and no `AGENTS.md`. The markers carry the SHA-256 of the lines between them (the first 16 hex digits)
and the UTC time that text last changed; change it in the channel first, never in one copy.

### Agents and the rules they follow

- **When several agents run in parallel, they may communicate through a shared channel.** How it
  works depends on the local setup and is defined in the optional `.AGENTS.md` or fed into every
  agent's context when it starts.
- **Every repository has exactly one responsible agent, and one agent may be responsible for
  several.** Which one is defined in the optional `.AGENTS.md` or fed into every agent's context when
  it starts.
- **A rule that holds for more than one repository is stated generally and shared** - here, or in the
  shared block of `.AGENTS.md` if it concerns the local setup. A rule is one or two sentences and
  says only what matters.
- **A rule belongs in `.AGENTS.md` only if it concerns local settings that cannot be shared through
  `AGENTS.md`.**
- **An agent writes only in its own repositories and asks the responsible agent for anything from
  another**, in the channel. The coordinating agent may also read the other repositories, but never
  writes in them.
- **Where a channel exists, an agent stays reachable on it while any work is open**, and says there at
  once when it waits on the operator.
- **A contract between repositories - an interface, a file format, a path another repository links
  to - changes only after agreement in the channel or by an operator decision**, and in both
  repositories.
- **An ambiguous answer is asked about once, plainly, instead of guessed at.** When an answer
  changes, everything built on the old one is looked for.

### Commits and pushes

- **While working, commit in logical steps, so a mistake can be rolled back.** A task is pushed only
  when it is finished, squashed into one commit as its last step; several finished tasks may go out
  in one push, one commit each.
- **An edit replaces an exact block, never everything between two landmarks; only the files you
  edited are formatted.** Before a commit, `git status` is read and only your own files are added,
  never with `git add -A`.
- **Unless the operator says otherwise, the operator pushes, and agents commit and stop.** An
  instruction to push covers exactly what it names.
- **A change that needs another repository's change names that dependency when it is handed over**,
  and is pushed only after the change it needs has built and published.
- **A change is ready only when every step its build workflow runs - not only Maven - has passed
  locally.**
- **Amend, squash or rebase only commits that are not pushed**, checked with
  `git ls-remote origin refs/heads/main`, never with a cached `origin/main`. A pushed commit is
  repaired by a new commit on the remote's tip, never by a force push.
- **A commit message is one brief line saying in words what changed**, never by a requirement number;
  the reasoning goes into an issue or a decision.
- **No commit message, changelog entry, issue or document says who asked for a change or who made it**
  - no "as the operator chose", no agent's name; it says what changed and why.
- **A change to what ships or builds gets its changelog entry in the same commit**, under
  `[Unreleased]` and the heading of its kind. A generated changelog is changed only through its
  sources, never by hand.

### Issues

- **Every open task is an issue in `issues/`, named `<prefix><nn>-Short-Title.md`** with one of its
  repository's prefixes; a repository may own several, for subgroups. An issue is one task, says
  what must be true and how it is judged done, and names a dependency on another repository's issue
  by repository and number.
- **A new issue follows this skeleton**, and its index row is added in the same commit; *Why* and
  *The shape* may follow *What must be true* in a larger issue:

      # <PREFIX><nn> — <Short Title>

      **Status:** Idea | Accepted | Analyzed | Estimated | Backlog | Implementing | Review | Rework;
      blocked by <repository> <number>, if so.
      **Size:** XS | S | M | L | XL - one line why (from Analyzed on).

      **What must be true.** One or two sentences, from the point of view of whoever uses it.

      ## Plan

      - The base design, a page at most, read in today's code: what it touches, the steps, the test
        that goes red first (from Analyzed on).

      ## Acceptance

      - How it is judged done: what is measured, and what must be seen to fail.

      ## Open questions

      - What the operator must decide; deleted once answered.

      ## To be checked

      - What the agent must still measure, read or confirm itself; deleted once done.
- **An issue moves through these states, and only the operator, as product owner, sets six of them**:
  - **Idea** - anyone brings it: a title and *What must be true*.
  - **Accepted** or **Rejected** - the operator only; a rejection says why.
  - **Analyzed** - the agent owning the repository: the *Plan* (a base design of a page at most, read
    in the code), the *Size*, the operator's decisions under *Open questions*, and what it must still find out
    itself under *To be checked*.
  - **Estimated** - the operator only, after reading the design and settling its questions with the
    agent, who adjusts the plan and the size.
  - **Backlog** - the operator only: it is built in the next round; the order in the index is the
    priority.
  - **Implementing** - the agent: **Rework** before Backlog; built locally, its whole workflow green on
    the VM, then pushed by the coordinator.
  - **Review** - the agent, once it is pushed and published as a snapshot; the operator tests what is
    published.
  - **Rework** or **Finished** - the operator only, after the test; a rework says what was found.

  An agent never sets Accepted, Rejected, Estimated, Backlog, Rework or Finished; it proposes in the channel. A
  size or plan that grows clearly while building is said before building on.
- **`issues/README.md` is the index, grouped by state** in this order: Review, Rework, Implementing,
  Backlog, Analyzed, Accepted, Idea, Estimated, then Finished and Rejected last; each group a table of
  number, size, blocked by and what it covers, and in *Analyzed* also the number of open questions
  (the group's heading says the state; a note on how far it is goes into what it covers), ordered by
  what to do next; it changes in the same commit as the issue. An open task is never a TODO in code or a note in a commit message.
- **A rejected issue's file is deleted**; its number, title and one sentence why stay as a row under
  *Rejected* in the index, so the same idea is not brought twice.
- **From Analyzed on, an issue has a size and a plan, read in the code, not guessed.** XS is under an hour (one
  file, no contract); S a few hours (one module and its tests, no contract other repositories read);
  M about a day (several modules, or one contract change, docs, an acceptance step); L several days
  (crosses repositories, or a design to decide first); XL is too big to build as one and says how it
  splits into issues of L or smaller. *Plan* names the files and classes it touches, the steps and the
  test that goes red first; a size or a plan that turns out wrong while building is corrected in the
  issue in the same commit.
- **An open question is one only the operator can answer** - a choice of product, scope, account,
  money or risk - and sits under *Open questions*, which the index counts in *Analyzed*: that column
  is what waits on the operator. From Estimated on an issue has none; one that comes up while building
  is said in the channel, and the issue waits until it is answered. What the agent can find out itself, by reading, measuring or asking another agent,
  sits under *To be checked* and is not counted. An answered question is deleted once its answer is
  in the criteria, the design or a decision.
- **A finished issue's file is deleted when the operator sets Finished, with every mention of its
  number, in one commit**; its number, title and one sentence of what was built stay as a row under
  *Finished* in the index, so what was built can be seen. What outlives it
  moves first: to `doc/` if a user needs it, to `doc/decisions.md` if it is a decision, to
  `AGENTS.md` if it is a rule, to `.AGENTS.md` if it is true only on one machine.
- **`doc/decisions.md` opens with an index** - subject, one line of what holds, link - and a row is
  written with its decision. An accepted risk states the exposure, why it stays, and what would
  change the answer.
- **A requirement not yet placed waits in `sokar-project`** until it is clear which repository builds
  it, then moves there as an issue.
- **Every row of an index opens with an anchor named after its number**, `<a id="<number>"></a>`,
  the number lower case; a *Finished* or *Rejected* row keeps it.
- **An issue number in `issues/` is a link to its row in the index, never to its file**, which is
  deleted when the issue is finished: `[<NUMBER>](README.md#<number>)` in the same repository, the
  other repository's index on GitHub for another's. The row outlives the file, so the link does not
  break. The shared tools check that every number is such a link and that its anchor exists.
- **Code, comments, test names and anything that ships never cite an issue number**; they state the
  constraint itself, since the issue is deleted once it is finished.

### Testing and machines

- **Work is tested on local VMs before it is handed over, on rented machines reachable from here only
  when the operator says so, and in the GitHub build on every push.** Which machines a setup has is
  defined in the optional `.AGENTS.md` or fed into every agent's context; machines are rented only
  through the shared tooling, and nothing else names a provider.
- **How agents exchange files for a test on a local VM is defined in `.AGENTS.md`.** Where it says
  nothing, a test uses only the agent's own artifacts.
- **A local VM is restarted only when agreed in the channel or asked of the operator.** A rented
  machine reachable from here is restarted only by the operator.
- **A rented machine's name says which agent and which run made it.** Every run deletes exactly what
  it created, after a failure too, and never sweeps by age or touches a machine it cannot attribute.
- **A script that changes a machine refuses to start over leftovers, removes only what it created -
  on interrupt too - and exits non-zero naming anything it left.**
- **Before a run on a shared machine, check what it does to that machine and say so.**
- **Before a long run, say how long it will take**, from a measured time, or say that it is a guess.
- **A test that waits on an agent, a model or any paid service fails fast on a loop**: it stops as
  soon as the same failure repeats, instead of waiting out its time, and names what repeated. A run
  seen looping is reported at once, so the operator can cancel it.
- **A defect is fixed only after a unit or integration test reproduces it.** The test fails first,
  then the fix makes it pass.
- **A test is trusted only once it has been seen to fail**: break the code on purpose, watch the
  test go red, and restore it. A break that does not compile proves nothing.
- **A guard asserts what its reference set is, not only that it has entries**: an empty set, one
  that cannot change, and one nobody reads all pass as green as one that works.
- **A test asserts the observable effect, never what the system reports about itself or its
  internals.** A fixture states what the real system produces, never what the code assumes, and is
  never edited so that a feature has something to show.
- **A test depends on nothing outside itself**: not on the machine's configuration (a suite calling
  git runs with `GIT_CONFIG_GLOBAL=/dev/null`), not on wall-clock time, and not on a pinned version
  written into it - it reads the version from the build.
- **Before a commit, the full suite runs as its own step and its result is read.** A commit is gated
  on the exit code, never on grepped output, and never chained onto the test run.
- **A change handed over says which test levels actually ran** - unit tests, local VM, rented
  machine - never which ought to have. A change to documents or issues only needs the tests tagged
  `documents`, which run alone; every test that reads a document carries that tag, and a test fails
  when one does not.
- **A repository with a workflow runs `sokar-release`'s `check-shared`, `check-citations` and, where
  there is a documentation chapter, `check-doc-site`, with the tests tagged `documents`, on every
  push and pull request**, on a GitHub runner; the build skips a change to documents only.
- **A test result names every skipped test**, never just a count.
- **A failing check prints what it asked and what it got, never a guessed cause**, and its failure
  path has been made to happen once and read.

### Claims and writing

- **Measure before you claim, and say what was measured and what inferred.** Agreement between agents
  counts only where each measured, and "I could not get X" only once a second method failed too.
- **A command that should have changed something is checked by observing the change** - connect,
  read the file, ask the daemon - never by its exit code alone.
- **Every date, time or age written is read from the system (`date -u`) first, never from memory.**
  An age is computed from two timestamps that were both read.
- **When a thing is right in two forms, keep one.** The second copy is the one that goes stale.
- **Documentation and rules state what is true now**: no dates, no history, no stories, and nobody
  named, only roles. The shared blocks' markers are the one exception.
- **Everything written is US English** - documentation, issues, comments, commit messages, the
  channel. Replies to the operator are in the language the operator writes in.
- **A finding worth keeping is committed** - in an issue, the documentation or a decision - never
  left only in a conversation or the channel.
- **A finding taken from a third-party source is written as the finding, never naming the source**,
  in anything committed.

### Security and tools

- **A secret never appears in a command line, a log line, a file name or an answer.** It reaches a
  process through its environment or standard input, is stored only encrypted and readable by its
  owner, never touches a filesystem in CI, and is never promised to be wiped from memory in Java or
  Dart - only kept in fewer copies for less time.
- **Every download follows redirects (`curl -L`), since any server may answer with one, and is
  checked against the digest its source names before it is used.** Credentials are never passed on
  to another host (`--location-trusted` is never used).
- **Input from outside - upstream metadata, the environment, a file or an answer from another
  program - is validated before it reaches a file, a command or a decision.**
- **A binary run with more rights than its caller is taken by path and refused when it or its
  directory could have been placed or changed by anybody else.**
- **`pkill -f` and `pgrep -f` match their own command line**, and can kill the shell running them;
  use a bracket pattern like `[p]odman`.
- **A long build or suite is watched through `tee` into a file**, never through a pipe into `grep`,
  which holds everything back until the end and looks like a hang.
- **`ssh -n host 'bash -s' <<EOF` runs nothing and exits 0**; a script sent on standard input goes
  without `-n`.

### Skills

- **Skills come from `https://fuinorg.jfrog.io/artifactory/agent-skills/`**, one reviewed package
  per skill, readable without credentials; each repository's own part names only which skills it
  uses, by slug.
- **Fetch, check and unpack them like this**, with the skills directory of your own harness (Claude
  Code reads `~/.claude/skills/<slug>/`):

      BASE=https://fuinorg.jfrog.io/artifactory/agent-skills
      curl -fsSL $BASE/.skills/skills.json                         # every slug, latest version
      curl -fsSL -o s.zip $BASE/<slug>/<version>/<slug>-<version>.zip
      curl -fsSL $BASE/../api/storage/agent-skills/<slug>/<version>/<slug>-<version>.zip  # its sha256
      unzip -q -d <skills directory>/<slug> s.zip

- **A harness that cannot install a skill reads its `SKILL.md`** from a directory outside the
  repository.
- **A skill is knowledge, not authority**: where it and a measurement disagree, the measurement
  wins, and a finding from reading code against a skill is a guess until a failing test reproduces
  it. Whether a skill is loaded is asked of the harness, not read from a directory.

### Code and builds

- **Fail closed and loud**: when a dependency is unreachable, a key is unknown or a check cannot run,
  stop with a non-zero exit code and say why; inside a program, an exception that says why does the
  same. Never silently do less.
- **A comment says why, never what, in one line where it can.** Reasoning that does not fit goes into
  documentation or a decision, and a small named method is preferred over a comment explaining a
  block.
- **Dot files are not committed.** `.gitignore` ignores `.*` and excepts only what a build needs -
  `.github`, `.gitignore` and `.gitkeep`, in a Maven repository also `.mvn` - and what is true of one
  machine goes into `.AGENTS.md`.
- **Maven is the build system: a Java repository builds, checks and tests with Java and Maven only.**
  Another build tool only where the platform a repository builds for supports no other - an IntelliJ
  plugin, since JetBrains supports only Gradle - named with the reason in its repository's own part. A
  file that cannot be Java - `mvnw`, or a script that runs where there is no Java yet - is named in its
  repository's own part with the reason, and no repository keeps a copy of a helper another one has.
- **Maven modules that belong together - the layers of one program, its packages - are submodules of
  one module of packaging `pom` named for what they share**, so the root lists parts, not pieces.
  Grouping changes only `parent` and `relativePath`, never a `groupId` or `artifactId`.
- **Every directory with a `pom.xml` has a `README.md`**: two or three sentences on what the module is
  and is not, a link to each of its submodules, and only the few technical facts that matter most, such
  as what it must never depend on; details are linked in `doc/`, never repeated. `sokar-release
  check-readmes` holds it.
- **Only what another repository or an outsider builds against is published to Maven Central, each such
  module opting in; the root and every grouping module are never published.** Every published pom is
  flattened, so it names no parent and a consumer needs nothing but the module itself; `sokar-release
  check-releases` refuses a release whose published pom still names one. A BOM is imported, never a
  parent.
- **Every Sokar Maven repository takes `org.fuin.sokar:sokar-parent` as its parent** and declares only what
  is its own; one that publishes declares `flatten-maven-plugin` by name in its root, and the parent's
  management flattens every module. `sokar-parent` names no parent and uses nothing of another Sokar
  repository.
- **Every Java package with main code is `@NullMarked` and checked by NullAway as an error when it
  compiles**, with a test that fails on an unmarked package.
- **`Files.move` with `ATOMIC_MOVE` replaces a file that already has the target name**; where the
  first of two writers must win, publish with `Files.createLink` (`link(2)`), which fails on an
  existing name.
- **Java code carries brief Javadoc on every public type and method; a test method's name reads as a
  sentence (never `testXxx`) and an assertion states its reason (`.as(...)`).** Every build call in
  CI names its repositories in the repository itself (Maven: `-s settings.xml`).
- **Everything a build runs is pinned and moved only by review**: actions by commit with the version
  beside it, the JDK (from `sokar-machines jdk --github`), the build tool's wrapper (Maven, Gradle) and
  images by version and digest, updated by Dependabot weekly, in one group, after three days.
  `sokar-release check-actions` enforces it.
- **While it is developed, a repository may build against another Sokar repository's snapshot from
  Sonatype**, and its build may fail for a while when that snapshot moves; a release depends only on
  releases, which the release build checks.
- **Packages are built online**: offline, the CycloneDX bill of materials skips itself with only a
  warning, and the package ships without it.
- **A publish is believed only once the published index shows the exact version**, probed with
  retries; a snapshot version sorts above the one before it. Retiring a package removes it from the
  index too.
- **Every native executable is built with `-march=x86-64`**, so it starts on any x86-64 CPU, and the
  build checks each executable for exactly that instruction set.

> **END Shared Area** · sha256 `8747535e984a49b5`

## What this repository is

The Matrix transport: it carries messages Sokar's filter has let through between tasks, and a person's words,
over a Matrix homeserver. Sokar's side of it - the peer table, a task's account, the token, the egress - is
`sokar`'s, with the contract this transport keeps.

- **A transport decides nothing and never parses a message.** What may pass is the filter's, in
  `sokar-message-sluice`, so "was this checked?" never depends on the route a message took.
- **Sokar drives a transport by placing files and running it as a program**, a CLI over files that nothing
  compiles against; its contract, `doc/transport.md`, changes here and in `sokar` together.
- **The homeserver is a service beside Sokar, never a task.** On one machine the transport reaches it on
  loopback, and nothing crosses the host boundary.
- **A task's Matrix account lives exactly as long as the task.** The signature on a message is the
  installation's, not the account's, so removing an account loses no attribution.

## Work in this repository

- **Issue prefix: `MX`.**
- **The tests tagged `documents` run alone with `./mvnw -B -s settings.xml -Pdocuments test`**;
  `DocumentTestsTaggedTest` fails when a test reads a document without the tag. None reads one today: the
  `shared-rules` workflow checks them with `sokar-release`'s `check-citations`, `check-doc-site` and
  `check-readmes`, on every change; the build skips a change to documents alone.
- **The homeserver image's pin is moved by review.** Dependabot proposes a new digest for the `FROM` line of
  `homeserver/Dockerfile`; `ghcr.io` gives it no publication date, so the reviewer reads the image's
  `org.opencontainers.image.created` and takes nothing younger than three days.
- **Installed machine-wide, this transport is polled by every account's `sokard`** and answers 78 where it
  is not configured, so a machine-wide install is only for an announced test and is removed after it.

## Skills

`graal`, `java-code-review`, `test-quality`, `security-audit`, `concurrency-review`, `clean-code`,
`solid-principles`.
