# MX107 — The Build's Outside Downloads Come From A Cache

```json
{
  "$schema": "issue-1.json",
  "id": "MX107",
  "title": "The Build's Outside Downloads Come From A Cache",
  "state": "Implementing",
  "size": "M",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "Build, Shared rules and Workflow check take the pinned GraalVM, the homeserver's image, the Ubuntu packages and the Maven tools from a cache keyed by their digest or version, keeping only the newest entry, so a registry or mirror that is down does not turn them red"
}
```

**Why this size.** Four downloads across three workflows, each with its own cache: the homeserver image both
through podman and as the binary the no-podman path unpacks. Plus the removal of older entries and one green run on each
forge. Larger than S because the image takes two paths.
**How it stands.** Implementing (2026-11-11): urgent on the operator's word (Agent Coordinator, 14:13Z), built by one
external subagent and reviewed here; the changed workflows' own Build is the test, no VM run. Filed for the
porelo-project story ST317, after porelo-host's molecule went red on a registry's 504.

**What must be true.** A build, shared rules check or workflow check whose inputs have not changed downloads nothing
from outside the forge except the Maven artifacts it newly needs. Each cache entry is keyed by the input's digest or
version, and a run that writes a new entry removes the older ones for the same input, so the cache holds only the
newest state.

## Why

Today only Maven's local repository is cached, in Build and Shared rules, keyed on `pom.xml` with a `maven-` restore key, so older
entries stay until GitHub evicts them. Every run downloads, uncached:

- **The pinned GraalVM**, installed by `org.fuin.porelo.machines.Main jdk --github` in Build and Shared rules.
- **The homeserver's image** `ghcr.io/matrix-construct/tuwunel@sha256:…`, pinned in `homeserver/Dockerfile`. The tests
  pull it with podman. A runner without podman fetches its manifests and layers from ghcr.io with curl and unpacks
  `usr/bin/tuwunel`.
- **Ubuntu's `rpm` and `zlib1g-dev`**, through `apt-get` in Build.
- **The ci-tools in Workflow check**, which has no Maven cache at all.

## Open questions

- Can GitHub's repository settings remove cache entries after a set time? Unused entries are evicted after 7 days
  today. Otherwise a step removes the older keys with `gh cache delete`, which needs `actions: write`.
- Is the apt download worth a cache, or is it the smaller risk next to an image registry?
