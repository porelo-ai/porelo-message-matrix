# MX110 — The Gate's Body Limit Test Fits In One Gigabyte

```json
{
  "$schema": "issue-1.json",
  "id": "MX110",
  "title": "The Gate's Body Limit Test Fits In One Gigabyte",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 4,
  "blockedBy": [],
  "covers": "`GateRequestTest` runs under `-Xmx1g` like the rest of the suite, a body at the gate's 64 MiB limit still read whole and one byte over still refused, in both framings"
}
```

**Why this size.** one test and possibly the gate's body reading in `GateRequest`, with no contract change.

**What must be true.** The unit suite runs whole in test forks of at most 1 GB heap. A request body of exactly the gate's
64 MiB limit is still read whole, and one byte more is still refused, for `Content-Length` and for chunked framing.

## Why

Under `-Xmx1g`, `GateRequestTest` alone ends in "Java heap space", so it runs in a fork of its own with `-Xmx2g`
until this is done. A test of a 64 MiB limit that needs more than 1 GB holds the body several times over, in the test
or in the gate itself; in the gate that would also be memory an oversized upload costs on a host.

## Plan

- **Measure first, in `enforcesTheBodyLimitForBothFramings`:** where the copies are. Read today: `GateRequest.read`
  fills two `ByteArrayOutputStream`s, `body` and `wire`, each growing by doubling, and `toByteArray()` copies each once
  more; the test then asserts `containsOnly((byte) 'x')` over the 64 MiB array, which AssertJ may box.
- **If the test holds the copies:** assert the size and the bytes without boxing (a loop, or a digest compared with one
  of the expected body).
- **If the gate does:** read a body of known `Content-Length` into one array of that size, and keep `wire` as the
  head plus that array instead of a second copy of the body, so a request at the limit costs about its own size.
- **Red first:** the test under `-Xmx1g`, which ends in "Java heap space" today; the four cases (two framings, at and
  over the limit) stay as they are.

## Acceptance

- `./mvnw -s settings.xml test -Dtest=GateRequestTest -DargLine=-Xmx1g` passes; it failed with "Java heap space" before.
- The at-limit cases still read 64 MiB whole and the next request after it; the over-limit cases are still refused.
