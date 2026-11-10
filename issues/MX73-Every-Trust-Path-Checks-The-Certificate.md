# MX73 — Every Trust Path Checks The Certificate

```json
{
  "$schema": "issue-1.json",
  "id": "MX73",
  "title": "Every Trust Path Checks The Certificate",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 30,
  "blockedBy": [],
  "covers": "Each `checkServerTrusted` overload refuses an unknown chain, and a file with several CAs trusts them all, under test"
}
```

**Why this size.** one new test class over `Trust`, with certificates the build already makes or a fixed pair.

**What must be true.** Each `checkServerTrusted` of the trust manager refuses a chain neither trust source knows, whichever overload is called, and a file with several CAs trusts all of them. Today only the path the HTTP client takes is held.

## Plan

`TrustTest`, new: the socket, engine and two-argument overloads each throw for an unknown chain and pass for a known one; a PEM file with two CAs trusts a server of the second.

## Acceptance

- Measured: PIT kills the mutants of the three overloads and of the alias counter.
- Seen to fail: each case.

## Checked

`TlsTest` holds the engine's overload against a real server, through the HTTP client, which takes no other. `TrustTest`
holds all three by a source of trust that records how it was asked, and a file of two authorities and a stranger by real
certificates. The engine's overload with a real certificate needs a handshake in progress, so `TlsTest` keeps that case.

## Measured

PIT on `Trust`: 6 killed, 21 timed out, both detected, including the three overloads and the alias counter. Left: line
233, what `TrustingEveryone` names as issuers, with checking switched off.
