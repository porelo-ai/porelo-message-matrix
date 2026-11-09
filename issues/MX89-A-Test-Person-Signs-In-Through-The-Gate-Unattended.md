# MX89 — A Test Person Signs In Through The Gate Unattended

**Status:** Implementing.
**Size:** S - one test-scope program and its tests in this repository; it ships nothing and changes no contract.

**What must be true.** A rehearsal on a host, with no person at a keyboard, signs the test person into the project's
room through the host's gate exactly as Element's *Continue with SSO* does, and then posts, reads and logs out, so a
person's side of the room is proven end to end without a browser and without any login a real host does not offer.

## Plan

- **A program in `src/test`, `RehearsalClient`, run through Maven with the test classpath**, given the local port of
  an ssh forward the rehearsal made as the test person's host login to the gate's socket. It ships in no package.
- **Sign-in is the gate's SSO and nothing else:** `GET /_matrix/client/v3/login/sso/redirect/porelo` with
  `redirectUrl=http://localhost:<port>/`, `Host: localhost:<port>`, no `Origin` and no `Sec-Fetch-Site`, as a
  browser's navigation sends it; the `loginToken` is read from the 302's `Location`, which is not followed; then
  `POST /_matrix/client/v3/login` with `m.login.token`. A password login, a registration token or any other flow is
  never tried.
- **Verbs:** `sign-in`, `post <file>` (the file's UTF-8 text as one `m.text` event, optionally with the `m.mentions`
  and `matrix.to` pill shapes the transport's tests already hold from Element), `read` (every member's lines in order,
  with sender and display name), `direct <account> <file>` (an unencrypted direct chat), `log-out`.
- **The session's token stays in the program's memory**; one run holds the session from sign-in to log-out, reading
  its steps from standard input, so the token is never written, printed or passed on.
- **Fails closed and loud:** any answer it did not expect stops it non-zero, naming what it asked and what it got,
  never the token; a repeated failure stops at once.
- **The test that goes red first:** against the gate in front of `FakeSocketHomeserver`, a run signs in and its
  session answers `whoami` as the test login; beside it, a gate answering the SSO redirect without a `loginToken`, or
  a login answer without a session, stops the run non-zero, and no output of any run holds the token.

## Acceptance

- Against a real host on a local VM: the program signs in as the test person, posts a line others see under that
  person's name, reads the room, and after `log-out` the session answers 401.
- The host's gate configuration is the same as any host's: no rehearsal setting exists.
- The tests fail when the token appears in output, and when the sign-in tries any flow but SSO and token.
