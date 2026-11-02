# MX75 — A Stalled Client Is Closed

**Status:** Accepted.

**What must be true.** A client that stops halfway through a request loses its connection to the gate once the idle limit
has passed, and one that finishes in time keeps it. Whoever removes the idle watch, or lets it fire on a request that
arrived whole, sees a test fail. Today no test reaches the watch, and a stalled client that is never closed holds a gate
process for as long as it likes.
