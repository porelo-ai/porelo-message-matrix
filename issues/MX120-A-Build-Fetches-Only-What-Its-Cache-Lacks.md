# MX120 — A Build Fetches Only What Its Cache Lacks

```json
{
  "$schema": "issue-1.json",
  "id": "MX120",
  "title": "A Build Fetches Only What Its Cache Lacks",
  "state": "Review",
  "size": "XS",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "The Maven cache of the Build and the shared rules check is saved again whenever the dependencies resolved differ from what was restored, so a moved parent or BOM is fetched from Maven Central once, not on every run, and a 429 from Central cannot fail a Build"
}
```

**Why this size.** The shared Maven cache form taken over inline into the two workflows, with one new composite action; no code, no contract.

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

- **The shared form, inline**, since this repository has no `pinned-jdk` action: in `build.yml` and
  `shared-rules.yml` the Maven `actions/cache` step becomes `actions/cache/restore` keyed by the pom's hash, the run
  id and its attempt, restoring by the pom's hash and then by `maven-`, followed by a marker file.
- **`.github/actions/maven-cache-save`**, the last step of each workflow's one job with `if: always()`: it saves
  under the run's key only when a jar, pom or wrapper zip changed status after the marker (`find -cnewer`, since Maven
  dates a download by the server's Last-Modified). Its paths are the restore's, the local Maven repository and
  the wrapper's folder, as a cache is found only under the paths it was saved with.
- **Checked before the push:** both workflows and the action parse; `check-actions` passes; the save decision's script
  passes shellcheck and, run against a stand-in repository, saves nothing without a new file, saves after a file with an
  old mtime but a new status change, and saves nothing without the marker.

## Acceptance

- The first Build after the push logs "fetched after the restore, among others: ..." and saves; the next Build logs
  "nothing fetched after the restore: nothing saved", and its Maven output shows no artifact downloaded.
- `check-actions` passes on both workflows and the new action.

## To be checked

- Whether a scheduled shared rules check, on a different runner image, saves a cache of its own and how often.
