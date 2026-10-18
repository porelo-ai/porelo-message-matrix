# MX25 — Publish Without A Downloaded Client

**Status:** Estimated; blocked by porelo
[B49](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b49), whose `artifactory.sh` this takes
over once porelo's Artifactory smoke test and its next `Publish` are green with it.
**Size:** S - one workflow and one sourced script, no contract other repositories read; its proof is the next
publish on main.

**What must be true.** Nothing downloaded runs in the job that holds the Artifactory token: the Build publishes
the transport's packages with `curl` against Artifactory's REST interface, and Artifactory refuses bytes that do
not match the checksums sent with them.

## Plan

- `.github/workflows/build.yml`, the `build` job: the step `jfrog/setup-jfrog-cli` goes. Today it downloads the
  JFrog CLI into the job holding `JF_ACCESS_TOKEN`, with no checksum of what it fetches.
- `.github/scripts/artifactory.sh`, taken over from porelo as it stands once proven there: `art_login` writes the
  token into a curl config file of mode 600 under `RUNNER_TEMP`, with `set -x` suspended; `art_put FILE TARGET
  [PROPS]` uploads with `X-Checksum-Sha1` and `-Sha256` and the properties as matrix parameters.
- "Publish the packages" sources it, calls `art_login` with the token in its `env`, and replaces both
  `jf rt upload` loops with `art_put`: each .deb to `porelo-dist-deb/pool/main/p/<name>/` with
  `deb.distribution`, `deb.component` and `deb.architecture`; each .rpm to `porelo-dist-rpm/<channel>/`. The
  refusal of a release already in the index, and the indexing step after it, stay as they are.
- Red first: the step run against a stand-in HTTP server, as porelo's was checked, with a wrong checksum header
  refused and a missing token failing before any upload; `check-actions` green on the changed workflow.

## Acceptance

- The first push to main after it: "Publish the packages" uploads every .deb and .rpm package the Build made, and
  "The packages are indexed, not merely stored" finds every one of them in the snapshots index.
- No `jfrog/` action and no `jf` command is left in `.github/`.
- A `set -x` trace of the publish step shows no token.
