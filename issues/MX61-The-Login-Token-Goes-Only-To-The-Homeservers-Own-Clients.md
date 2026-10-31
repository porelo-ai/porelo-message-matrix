# MX61 — The Login Token Goes Only To The Homeserver's Own Clients

**Status:** Idea, 2026-10-31; before any real use of a host's gate (operator, 2026-10-31).
**Size:** S - one rule in `GateRules.redirectAllowed`, where the gate takes the clients it knows from, the doc's
section on the gate, and `GateRulesTest` and `GateIT` cases.

**What must be true.** The gate's SSO redirect hands a login token only to a client of this homeserver's own: one
whose redirect target the host names, such as Element Desktop's own scheme or a web client served at the person's
forward, `http://<host>.localhost:<port>`. A redirect to any other URL is refused (400) and issues no token.

## Why

Today the gate issues a login token to any absolute `http`, `https` or custom-scheme `redirectUrl`, without asking
the person. Their forward is reachable from their browser, so any web page they open can send the browser to
`http://<host>.localhost:<port>/_matrix/client/v3/login/sso/redirect?redirectUrl=https://<elsewhere>`. The gate then
signs that browser's caller in as the person and hands the token to the other site, which has that person's account
for as long as the session lasts. The token is single use and lives two minutes, which limits its reuse, not the
first use.

## Acceptance

- `GateRulesTest`: a `redirectUrl` to another origin, another custom scheme, or the person's forward on another
  port is refused; the host's named clients pass.
- `GateIT`: a redirect to a foreign origin through the gate gives 400 and leaves no login token in
  `gate/login-tokens/`.
- Seen to fail first: today's rule passes `https://example.org/`.

## To be checked

- Which redirect targets Element Desktop, Element Web and Porelo's own clients send, measured, and whether a
  confirmation page of the gate's ("Continue to <client>?"), as other homeservers show, is needed beside the list.
