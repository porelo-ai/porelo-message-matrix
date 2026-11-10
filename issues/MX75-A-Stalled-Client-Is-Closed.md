# MX75 — A Stalled Client Is Closed

```json
{
  "$schema": "issue-1.json",
  "id": "MX75",
  "title": "A Stalled Client Is Closed",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 25,
  "blockedBy": [],
  "covers": "A client that stalls halfway through a request loses its connection to the gate at the idle limit, and a test fails if that stops"
}
```

**Why this size.** the limit as an argument to `serve`, the watch saying it fired, tests over a socket.

**What must be true.** A client that stops halfway through a request loses its connection to the gate once the idle limit
has passed, and one that finishes in time keeps it. Whoever removes the idle watch, or lets it fire on a request that
arrived whole, sees a test fail. Today no test reaches the watch, and a stalled client that is never closed holds a gate
process for as long as it likes.

## Plan

`Gate.serve` takes the limit, so a test sets it short. `GateTest`, over a socket: a client stopping halfway loses its
connection and is answered nothing; two requests in time, each after a pause, keep it; closing for idling throws nothing
and logs nothing.

## Found

A connection closed for idling could be read as a bad request: `serve` told the two apart by whether the watch's thread
had ended, and it may still be running a moment after the close, so `serve` went on to answer a closed socket and threw.
Seen in one of 40 rounds before the fix. The watch now says it fired, before it closes, and the case now holds the watch
alive in its close, which makes the old check red every time.

## Measured

Each case seen red: no close, no interrupt, and answering after idling. PIT on `Gate`: 194 killed, 2 timed out, 5 left: DEL
at the edge of the log's mask (216), a position 0 no path or allowed redirect has (340, 496, 497), and an id with an empty
server (583), which the homeserver does not answer.
