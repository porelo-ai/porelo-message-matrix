# MX71 — The Gate's Request Parser Is Held To Its Refusals

**Status:** Backlog.
**Size:** S - table cases over one class's reading of a request.

**What must be true.** A request the gate's parser must refuse, one that could read differently to the gate and to the homeserver, fails a unit test if it ever passes.

## Plan

`GateRequestTest`, as a table of wire requests: a `Connection` other than `close` or `keep-alive`; a `Content-Length` with a hex digit and a chunk size with a non-hex one; a body and a chunked body of exactly the limit and one byte over; a header with no name, with `�` in its name or value, with a control character; a path ending in a cut-off `%`; `close()` for `close`, `Close`, `keep-alive` and none.

## Acceptance

- Measured: PIT on `GateRequest` leaves only mutants the issue names as equivalent or harmless, such as a `flush` before a close.
- Seen to fail: each case against its mutant.
