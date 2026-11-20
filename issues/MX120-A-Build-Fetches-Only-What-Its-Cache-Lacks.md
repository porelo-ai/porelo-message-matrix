# MX120 — A Build Fetches Only What Its Cache Lacks

```json
{
  "$schema": "issue-1.json",
  "id": "MX120",
  "title": "A Build Fetches Only What Its Cache Lacks",
  "state": "Implementing",
  "size": "S",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "The Maven cache of the Build and the shared rules check is saved again whenever the dependencies resolved differ from what was restored, so a moved parent or BOM is fetched from Maven Central once, not on every run, and a 429 from Central cannot fail a Build"
}
```

**Why this size.** The cache steps of two workflows, `build.yml` and `shared-rules.yml`, plus a check that a second run fetches nothing. No code or contract change.

**What must be true.** A Build whose dependencies did not change since the last saved cache downloads nothing from
Maven Central. A Build after the parent or an imported BOM moved downloads what moved once, and the next one nothing.
A rate limit at Central fails a Build only when a dependency is really missing from the cache.

## Why

Both workflows restore the local Maven repository with `actions/cache` under the key
`maven-<hashFiles(pom.xml, maven-wrapper.properties)>`. The versions come from `porelo-parent` (a snapshot) and the
BOMs it imports, which move without this repository's `pom.xml` changing. On an exact hit, `actions/cache` never saves
again, so what moved since the cache was made is fetched from Central on every Build. Another Porelo repository's Build
using the same key form failed this way with 429 Too Many Requests.

## Plan

- **Split restore and save in both workflows:** `actions/cache/restore` by the prefix `maven-`. After the build, a key is
  made from the digest of the sorted list of released artifacts in the local Maven repository, leaving out
  `*-SNAPSHOT` folders and `_remote.repositories`/`*.lastUpdated` files. `actions/cache/save` runs with that key when
  it differs from the one restored; actions stay pinned by commit with the version beside them.
- **Snapshots** of Porelo repositories stay online by `settings.xml`'s `updatePolicy` `always`, as they must, and
  are left out of the key so they do not save a new cache on every run.
- **Red first:** read in the last Build's log which artifacts were downloaded from Central after an exact cache hit.
  After the change, two Builds in a row on an unchanged pom show no download from Central in the second.

## Acceptance

- The second of two Builds on the same commit logs no `Downloading from central` and saves no new cache.
- After a moved parent snapshot, one Build saves a new cache and the next one downloads nothing released.
- `check-actions` passes on both workflows.

## To be checked

- Whether `porelo-parent` or an imported BOM uses a version range, which Maven resolves online every run whatever is
  cached. If one does, it is reported to its owner.
- The last Build's log for downloads after the cache hit. The public API refused this machine for its rate limit
  while it was filed.
