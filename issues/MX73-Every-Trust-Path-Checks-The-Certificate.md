# MX73 — Every Trust Path Checks The Certificate

**Status:** Implementing.
**Size:** S - one new test class over `Trust`, with certificates the build already makes or a fixed pair.

**What must be true.** Each `checkServerTrusted` of the trust manager refuses a chain neither trust source knows, whichever overload is called, and a file with several CAs trusts all of them. Today only the path the HTTP client takes is held.

## Plan

`TrustTest`, new: the socket, engine and two-argument overloads each throw for an unknown chain and pass for a known one; a PEM file with two CAs trusts a server of the second.

## Acceptance

- Measured: PIT kills the mutants of the three overloads and of the alias counter.
- Seen to fail: each case.

## To be checked

- How `Trust`'s existing coverage is reached, as there is no test class of that name; a new case goes beside it if one holds it already.
