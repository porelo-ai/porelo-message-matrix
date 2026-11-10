# MX89 — A Test Person Signs In Through The Gate Unattended

```json
{
  "$schema": "issue-1.json",
  "id": "MX89",
  "title": "A Test Person Signs In Through The Gate Unattended",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 2,
  "blockedBy": [],
  "covers": "A rehearsal signs the test person into the project's room through the gate's SSO without a browser, posts, reads and logs out; no login a real host does not offer"
}
```

**Why this size.** one test-scope program and its tests in this repository; it ships nothing and changes no contract.

**What must be true.** A rehearsal on a host, with no person at a keyboard, signs the test person into the project's
room through the host's gate exactly as Element's *Continue with SSO* does, and then posts, reads and logs out, so a
person's side of the room is proven end to end without a browser and without any login a real host does not offer.

## Plan

- **A program in `src/test`, `RehearsalClient`, run with the test classpath as `RehearsalClient --socket <path>`**,
  the path being the local Unix socket end of an ssh forward the rehearsal made as the test person's host login to the
  gate's socket (`ssh -L <local socket>:<gate socket>`). Its listener must be this account's. It ships in no package.
- **Sign-in is the gate's SSO and nothing else:** `GET /_matrix/client/v3/login/sso/redirect/porelo` with
  `redirectUrl=http://localhost/`, `Host: localhost`, no `Origin` and no `Sec-Fetch-Site`, as a
  browser's navigation sends it; the `loginToken` is read from the 302's `Location`, which is not followed; then
  `POST /_matrix/client/v3/login` with `m.login.token`. A password login, a registration token or any other flow is
  never tried.
- **Steps, one per line on standard input, each answered by one JSON line:** `sign-in`, `join <room>`,
  `post <room> <file> [@user...] [@room]` (the file's UTF-8 text as one `m.text` event; each named member and the whole
  room marked in `m.mentions`, as a client's pill marks them), `read <room>` (every member's lines in order, with sender
  and display name), `direct <user> <file>` (an unencrypted direct chat), `log-out` (then `whoami` must answer 401).
- **The session's token stays in the program's memory**; one run holds the session from sign-in to log-out, reading
  its steps from standard input, so the token is never written, printed or passed on.
- **Fails closed and loud:** any answer it did not expect stops it non-zero, naming what it asked and what it got,
  never the token; a repeated failure stops at once.
- **The tests:** `RehearsalClientTest`, against a scripted gate on a Unix socket: a run signs in only by SSO and token,
  as a navigation back to the forward's own page, and no output holds a token; a redirect without a `loginToken`, or a
  login answered without a session, stops the run non-zero; a failed step still logs the session out. `GateIT` runs the
  client against the real homeserver behind its gate: sign in, post, read back, log out.

## Acceptance

- Against a real host on a local VM: the program signs in as the test person, posts a line others see under that
  person's name, reads the room, and after `log-out` the session answers 401.
- The host's gate configuration is the same as any host's: no rehearsal setting exists.
- The tests fail when the token appears in output, and when the sign-in tries any flow but SSO and token.

## Measured

- On the foo-org host, a VM on the development machine with its real gate and no rehearsal setting, 2026-11-09 from
  21:16Z: the test person signed in through the gate's SSO, joined the project's room, posted a line the three tasks'
  records show under the person's name, read it back and logged out; `whoami` then answered 401. Every later
  [MX90](README.md#mx90) pass began and ended the same way, the last 2026-11-10 00:01Z to 00:12Z.
- `GateIT` runs the client against the real homeserver behind its gate, on the rented test host.
