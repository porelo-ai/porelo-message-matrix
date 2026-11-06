# MX79 — Only The Forward's Own Page Reads The Gate

**Status:** Review.
**Size:** S - two rules in `GateRules` and `Gate.sso`, one header kept by `GateRequest`, their tests and the gate's page in
`doc/homeserver.md`.

**What must be true.** A page in the person's browser that is not served at the forward the request came in on - a page
on another local port, such as the forge's web through the same tunnel - can neither read the gate's answers nor
register or sign in as the person, and no page elsewhere can start the person's sign-in.

## Why

The gate takes the ssh peer's login as who asks, so a request needs no token of its own to register a free name of the
login and receive its access token. The gate refuses a foreign `Host` and a foreign `Origin`, but takes every loopback
`Origin` on any port, not only the forward's own. A script on another loopback site in the person's browser passes, reads
the answers, and can register `@porelo.<login>` before Porelo does, holding an account the host's `keep-room` invites.
The SSO redirect refuses any `Origin`, but a navigation sends none, so a page elsewhere can still start a sign-in, which
makes the person's bare account if missing.

## Plan

- `GateRules.originAllowed(origin, host)`: none, `vector://vector`, or exactly `http://<host>`, case-insensitive; an
  `https` origin, another port or another loopback name is refused (403), and so are its CORS headers.
- `Gate.decide` and the CORS echo in `Gate`'s loop pass the request's `Host`.
- `Gate.sso`: a `Sec-Fetch-Site` other than `none` or `same-origin` is refused (400) and issues no token; `same-site`
  ignores ports, so it would take another local port. A client that sends none (Element Desktop's system browser before
  that header, a command line) is taken as today.
- `GateRequest`: `sec-fetch-site` read like `origin`, a second one refused as malformed.
- `doc/homeserver.md`: the gate's paragraph on the headers; the change log.
- Red first: `GateTest`: a register and a read from `http://127.0.0.1:8448` against the forward `walk.localhost:8448`,
  and from `http://walk.localhost:9999`, answered 403 with no CORS header; an SSO redirect with `Sec-Fetch-Site:
  cross-site` and `same-site` answered 400 with no token. Today they pass. The test that allows `http://127.0.0.1:8448`
  against `walk.localhost:8448` is corrected.

## Acceptance

- Under test: the forward's own origin and `vector://vector` still read the gate's answers and sign in; a request with
  no `Origin` (the transport, a desktop client) works as before.
- Seen to fail: a page on another loopback port or name, or `https`, reads an answer, registers or signs in; an SSO
  redirect with `Sec-Fetch-Site: cross-site` or `same-site` issues a token or makes an account.
