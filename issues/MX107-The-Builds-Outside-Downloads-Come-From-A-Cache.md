# MX107 — The Build's Outside Downloads Come From A Cache

```json
{
  "$schema": "issue-1.json",
  "id": "MX107",
  "title": "The Build's Outside Downloads Come From A Cache",
  "state": "Review",
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
**How it stands.** Review (2026-11-11): built by this repository's agent; the changed workflows' own runs are the
test, no VM run. The image step was run on the development machine with an empty cache, with a cache and no network,
with and without podman, with a stray and a damaged file. Urgent on the operator's word (Agent Coordinator, 14:13Z),
filed for the porelo-project story ST317 after porelo-host's molecule went red on a registry's 504.

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

## Plan as built

- **The pinned GraalVM**, in Build and Shared rules: `machines.Main jdk --print-sha256` names the pinned digest, the
  archive is restored under `pinned-graalvm-<sha256>`, and `jdk --github --cache <dir>` takes it from there, checked
  against the digest again, or fetches it and keeps it; the entry is saved only after a miss.
- **The homeserver's image**, in Build: its index, linux/amd64 manifest, config and layers are kept as fetched from
  ghcr.io, an OCI layout under `pinned-tuwunel-<digest>`. Each file is checked against its digest after a hit and
  fetched again only when it is missing or wrong; a file the image does not need is removed. With podman, the image
  is pulled from the layout, which keeps the pinned index's digest, and tagged by the Dockerfile's name, so the tests'
  `podman run` finds it and asks no registry; `podman image exists` on the full reference is checked. Without podman
  the binary is unpacked from the same layers, as before.
- **`rpm` and `zlib1g-dev`**: GitHub's runner image holds both, so the step checks them with `dpkg-query` and installs
  only what a runner lacks, with a warning; nothing is fetched from Ubuntu's archive where both are present.
- **Workflow check** restores and saves Maven's local repository as Build does.
- **Tests:** `HomeserverStepTest` runs the image step against a stand-in for ghcr.io: a second run asks no registry
  and drops a file the pin does not name (seen red with each removed), a damaged cached file is fetched again, and the
  pin names the reference and its digest or stops; its earlier cases hold as before.
- **The removal rule**: every key names its input's digest or the pom's hash, and a restore after a miss starts from an
  empty directory, so an entry holds only one state. An entry no run reads any more, after a moved pin, is removed by
  GitHub after seven days unused (and the oldest first when the repository passes its 10 GB). No step deletes entries,
  so no job needs `actions: write`.
