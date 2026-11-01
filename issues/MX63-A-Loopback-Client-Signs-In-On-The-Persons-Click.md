# MX63 — A Loopback Client Signs In On The Person's Click

**Status:** Idea.
**Severity:** Low - without it, desktop clients that take the SSO callback on a loopback port of their own, such as
nheko, cannot sign in behind the host's gate; Element Desktop and a page at the forward can.

**What must be true.** When a client asks the gate's SSO to send the person back to a loopback port of its own
(`http://127.0.0.1:<n>/…` or `http://localhost:<n>/…`), the gate first shows the person a page asking "hand this
sign-in to the application on port <n>?" and issues the login token only after the person confirms it there. The
gate never hands a login token to a loopback callback without that confirmation. A page that only navigates or
fetches gets no token.

## Why

A loopback callback cannot be told apart from a page on another local port that sends the person's browser to the
gate, so a token handed to it without the person's word could go to any local page. Today such callbacks are
refused with 400. The confirmation lets the person speak for the client they started.
