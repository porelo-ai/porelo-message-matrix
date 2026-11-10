# MX97 — A Verb Refuses A Repeated Flag And Repeats No Secret

```json
{
  "$schema": "issue-1.json",
  "id": "MX97",
  "title": "A Verb Refuses A Repeated Flag And Repeats No Secret",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "A lifecycle verb refuses a flag given twice with 64, and no failure line repeats a secret or a control character from a homeserver's error text"
}
```

**What must be true.** A lifecycle verb given the same flag twice, such as `--project a --project b`, stops with 64
instead of acting on the last one; and no failure line on stderr repeats a secret the transport holds or a control
character, even when the homeserver's error text carries one.

## Why

`Lifecycle.options` takes the last value of a repeated flag, so a caller's first `--project` is dropped without a word.
`MatrixClient` passes a homeserver's `error` text to the failure line as it came; a homeserver or a proxy that echoes a
request header would put the token there. Both hold for every verb, not only one.
