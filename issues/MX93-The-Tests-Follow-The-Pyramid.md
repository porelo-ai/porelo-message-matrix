# MX93 — The Tests Follow The Pyramid

```json
{
  "$schema": "issue-1.json",
  "id": "MX93",
  "title": "The Tests Follow The Pyramid",
  "state": "Accepted",
  "size": "L",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "Every flow proven by unit tests over fakes and the unit suite under a minute: today 57 % in-process unit tests, 31 % process tests in the unit suite (178 s), 12 % against the real homeserver; the lifecycle, room, direct and receipt paths only there"
}
```

**Why this size.** five fixes in this repository's tests and a few seams in the code they test (one M, three S, one XS, one
S for the split); no contract changes, but more than a few days of work in all.

**What must be true.** Every flow of the transport - its verbs' states, choices, limits and failure paths - is proven
by unit tests over fakes that run in seconds; the unit suite runs in under a minute, and the tests against the real
homeserver, real processes and real sockets add only what the base cannot show.

## Where the suites stand

Measured with `./mvnw -B -s settings.xml clean verify` on the JVM, on the development machine, at the commit that
placed the shared rule: 1082 tests, none failed; 8 min 31 s in all, compile and packaging included. The sums are each
test class's own time; surefire and failsafe run one class after another, so they are also the wall time of each step.

| Level | What runs it | Tests | Share | Time |
|---|---|---|---|---|
| Unit, in process: no process started, fakes in the same JVM (the homeserver fake answers on a loopback socket of the test's own) | surefire | 617 | 57 % | 55 s |
| Integration in the unit suite: a real child process (`podman` and helper stand-ins, `ssh-keygen`, a shell script, a credential helper), a real TLS handshake, a real file lock across processes | surefire | 340 | 31 % | 119 s |
| Integration: the real homeserver in podman, the real gate on a Unix socket, the native executable | failsafe | 125 (7 skipped on the JVM, run only by `-Pnative`) | 12 % | 318 s |
| End-to-end: the transport on a host as a person and its tasks meet it | the host rehearsal, by hand | 0 in the build | 0 % | - |

The skipped tests, all for want of a native build on the JVM run: `CpuBaselineIT` (1), `DumpableIT` (2), `PackagesIT`
(4); the Build's `-Pnative verify` runs them.

So the unit suite takes 178 s, three times the minute allowed, and two thirds of it are integration tests by the
pyramid's definition: by that definition the shares are 57 / 43 / 0, against 70-80 / 15-20 / 5-10.

The slowest classes of the unit suite: `PodmanUnitTest` 53 s (39 tests, real child processes waiting out their
diagnostics bound, about 3.4 s each), `RoomTest` 25 s (two credential-helper timeouts of 10 s each), `LocalHomeserverTest`
17 s (166 tests; a silent port and slow log traversal waited out, 4 s each), `GateRequestTest` 15 s (the body limit
streamed for real, 8 s and 7 s), the three `TlsTest` groups 17 s, `LayCertificatesTest` 10 s, `UnreachableTest` 8 s,
`GateTest` 8 s, `KeepWriterPreferenceTest` 7 s, `ForgetPositionTest` 5 s.

## Flows proven only against the real homeserver

- **The lifecycle's own paths** (`LifecycleIT`, 34 tests, 29 s; `CentralIT`, 27 tests, 41 s): `setup` making the
  conversation and a second `setup` keeping everything, `enroll` and `enroll` again replacing the token, `retire`,
  `rename` and the nickname, `join` and `part`, `clear` taking the conversation away but keeping another project's
  member, a reset homeserver said as such and a wrong token not taken for one, a second project, and the central mode's
  admission of a machine invited before or after it registered, a room open to guests refused, enrolled tasks on two
  machines talking. `LifecycleTest` (29) holds only the refusals and the homeserver not reached.
- **The room verbs' positions** (`RoomIT`, 16 tests, 63 s): the first read starting now, `read` showing only addressed
  lines while moving past every line, `--all`, `post` counting unread addressed lines without moving the position,
  `wait` waking only for an addressed line and the expected sender, mentions only of other members, a truncated
  timeline paged, each person and room needing a first read of its own, a position from another database refused.
  `RoomTest` (43) holds the credential helper, the arguments and edits.
- **Direct chats** (`DirectIT`, 5): a person opening a chat, a stranger declined, an encrypted chat declined and an
  encrypted message named, a task writing to a person. `DirectTest` (7) holds three of the invitation's edges.
- **Receipts** (`ReceiptIT`, 5): delivered then read, `read` marking read, a receipt reading up to an event, a
  receipt that cannot be said. `ReceiptTest` (2) holds a thread's receipt and the event's own room.

What stays at the integration level on purpose, because only the real piece shows it: the backup carried to another
machine (`KeepIT`), a member taken out of a room reading nothing after (`KeepRoomIT`), the gate in front of the real
homeserver and its concurrent registration (`GateIT`), a message arriving byte for byte once and the largest event
fitting (`SendIT`), another account's listener on the loopback port (`LoopbackOwnerIT`), and what the native executable
and the packages are (`CpuBaselineIT`, `DumpableIT`, `PackagesIT`).

## Tests that wait or reach the network where a fake would do

- `UnreachableTest.noSuchHost` resolves `no-such-host.invalid` through the machine's resolver: 8.4 s, and its result
  depends on the machine's DNS.
- `RoomTest.aSilentCredentialHelperCannotHangTheCaller` and `aTimedOutCredentialHelperLeavesNoRunningDescendant` wait
  out the real 10 s helper limit.
- `GateTest.aClientWhoseRequestsEachArriveInTimeKeepsItsConnection` sleeps 3.5 s twice against the real idle limit.
- `LocalHomeserverTest.silentPortEndsTheWait`, `diagnosticsBoundSlowLogTraversal` and
  `invalidAccountClearTouchesNothing` wait out real bounds of about 4 s; one helper thread sleeps 30 s until stopped.
- `PodmanUnitTest` waits out the diagnostics bound of real child processes in most of its 39 cases.
- `SharedLockLooper`, used by `KeepWriterPreferenceTest` and `ForgetPositionTest`, holds its lock for a random 1-3 s,
  so those tests' times and interleavings vary from run to run.
- `PollIT` sleeps 1.5 s, 1 s and 3 s before sending, to land a message inside a waiting poll.

## Plan

- **The lifecycle's paths over the homeserver fake (M).** `FakeHomeserver` already answers what a test queues and
  records what it was asked; `LifecycleTest` gains each path `LifecycleIT` and `CentralIT` show, as a script of the
  homeserver's answers and an assertion of the requests made and the files written. The IT keeps one happy path per
  verb and what only Tuwunel decides (a guest room's join rule, a token really invalidated). Red first: a path with a
  request left out of the code fails its unit test.
- **The room verbs' positions over the fake (S).** The position, unread count, paging and mention rules are read in
  `Room` as plain logic over a sync page; `RoomTest` gains each case of `RoomIT` over scripted pages. `RoomIT` keeps one
  read, post and wait.
- **Direct chats and receipts over the fake (S).** The same for `Direct` and `Receipt`; the ITs keep one chat and one
  receipt.
- **Every bound a unit test waits out is the caller's (M).** The credential helper's limit, the gate's idle limit, the
  homeserver wait's bounds, the diagnostics bound of `PodmanUnit` and the backup's lock budget are passed in, as
  `Progress` already takes a clock; the tests run them in milliseconds, and one integration test per bound keeps the
  real value. `SharedLockLooper` holds its lock until told, not for a random time.
- **The name lookup is the caller's (XS).** `UnreachableTest.noSuchHost` hands `MatrixClient` a failed lookup instead of
  asking the machine's resolver.
- **The suites split by level (S).** The tests that start a process, a TLS handshake or a lock across processes are
  tagged and run as their own step after the unit suite (or move into failsafe), so the unit suite is the in-process
  tests alone and its time is read in each build; a test fails that starts a process without the tag, as
  `DocumentTestsTaggedTest` does for documents.

## Acceptance

- The unit suite, in-process tests only, runs in under a minute on the development machine, measured in a clean build.
- Every flow named above under *Flows proven only against the real homeserver* has unit tests over fakes; removing a
  request or a check from the code fails a unit test, not only an integration test.
- No unit test sleeps, waits out a real bound or asks the machine's resolver.
- The shares by count are measured again and written into this issue before it goes to Review.

## To be checked

- Why `KeepRoomIT` takes 103 s for 4 tests and `GateIT` 38 s for 3: each starts the homeserver behind its gate, and
  sharing one start per class may be enough.
- Whether `PollIT`'s sleeps can be replaced by waiting for the poll's own "waiting" sign.
