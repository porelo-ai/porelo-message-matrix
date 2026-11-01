# MX64 — No Port Reaches The Homeserver Behind Its Gate

**Status:** Idea.
**Severity:** Low - today the forwarded port reaches nothing, but anything that later listens on the container's 8009
would be reachable from every account on the host, past the gate.

**What must be true.** On a host whose homeserver runs behind its gate, the homeserver's unit publishes no TCP port:
`127.0.0.1:8008` accepts no connection, and the gate's socket is the only way in.
