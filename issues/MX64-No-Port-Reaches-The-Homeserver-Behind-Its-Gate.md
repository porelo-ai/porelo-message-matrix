# MX64 — No Port Reaches The Homeserver Behind Its Gate

```json
{
  "$schema": "issue-1.json",
  "id": "MX64",
  "title": "No Port Reaches The Homeserver Behind Its Gate",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 4,
  "blockedBy": [],
  "covers": "In gate mode the homeserver's unit publishes no TCP port, so `127.0.0.1:8008` accepts no connection and only the gate's socket leads in"
}
```

**How much it matters.** Low - today the forwarded port reaches nothing, but anything that later listens on the
container's 8009 would be reachable from every account on the host, past the gate.

**What must be true.** On a host whose homeserver runs behind its gate, the homeserver's unit publishes no TCP port:
`127.0.0.1:8008` accepts no connection, and the gate's socket is the only way in.
