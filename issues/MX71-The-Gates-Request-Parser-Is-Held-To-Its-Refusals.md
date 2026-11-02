# MX71 — The Gate's Request Parser Is Held To Its Refusals

**Status:** Review.
**Size:** S - table cases over one class's reading of a request.

**What must be true.** A request the gate's parser must refuse, one that could read differently to the gate and to the homeserver, fails a unit test if it ever passes.

## Plan

`GateRequestTest`, as a table of wire requests: a `Connection` other than `close` or `keep-alive`; a `Content-Length` with a hex digit and a chunk size with a non-hex one; a body and a chunked body of exactly the limit and one byte over; a header with no name, with `�` in its name or value, with a control character; a path ending in a cut-off `%`; `close()` for `close`, `Close`, `keep-alive` and none.

## Acceptance

- Measured: PIT on `GateRequest` leaves only mutants the issue names as equivalent or harmless, such as a `flush` before a close.
- Seen to fail: each case against its mutant.

## Measured

PIT on `GateRequest`: 187 killed, 12 timed out, 1 out of memory, all detected; 5 left after the case for a chunk size in
letters. Equivalent or harmless:

- Line 164: a wire of exactly twice the body limit, its framing alone over the limit by one byte.
- Lines 410, 411, 420: `URI` refuses a cut-off escape before this check, and U+0080 is neither letter nor digit.

## Changed from the plan

A `�` in a header's value is not refused, and no case asks it now. The gate reads a header as ISO-8859-1, so the bytes of
a `�` arrive as three plain characters, and a byte above ASCII in a value is what RFC 9110 calls `obs-text`. Nothing here
reads such a value, and it goes to the homeserver as it came. A name with one is refused.
