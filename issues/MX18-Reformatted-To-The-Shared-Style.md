# MX18 — Reformatted To The Shared Style

**Status:** Review, published as `0.5.1~snapshot.61`; this repository's part of `porelo-project`
[PJ29](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj29).
**Size:** S - one module: the plugin switched on and one reformatting run over 59 Java files, of which 58 have
lines past 100 columns (963 lines in all), and five YAML files from 2 to 4 spaces; only whitespace, line
breaks and the order of imports change.

**What must be true.** This repository's Java follows the shared style: the shared `.editorconfig` is at its root,
`spotless:check` runs in `validate` and refuses a misformatted file, and the one commit that reformatted the code is
skipped by `git blame`.

## Why

[PJ29](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj29) puts the style in one shared
file and has the build check it; each Maven repository then switches it on and reformats once. The code here is
indented by four already, but wraps at 120 columns, not 100.

## Plan

- **Blocked by the shared part:** the `.editorconfig` and `spotless-maven-plugin` with `google-java-format` (AOSP)
  in `porelo-parent`'s plugin management, published as `porelo-parent` `0.2.1-SNAPSHOT`, which this repository
  already builds on.
- **Steps:**
  - copy the shared `.editorconfig`, which `check-shared` compares;
  - switch the plugin on in `pom.xml`;
  - run `spotless:apply` once, as a commit of its own;
  - in the same commit, the YAML indented by 4 spaces as the shared `.editorconfig` says: the three workflows,
    `.github/dependabot.yml` and `mkdocs.yml`, all at 2 today; `pom.xml` and `settings.xml` are at 4 already, and
    there is no `*.sh`, `*.feature` or `*.py` here;
  - the Shared Area taken anew from `porelo-buildtools`, which names `.editorconfig` and
    `.git-blame-ignore-revs` among the dot files that are committed, and `.gitignore` given `!.editorconfig` and
    `!.git-blame-ignore-revs`;
  - `persist-credentials: false` on both checkouts, since the same tools' `check-actions` refuses a checkout
    that keeps the job's token, and the commit would be red without it; no step pushes with git;
  - `mvnw` stays as the Maven wrapper generates it, indented by tabs, so that a wrapper update stays a plain
    replacement; the shared `.editorconfig` unsets its indentation;
  - put that commit's hash in `.git-blame-ignore-revs`.
- **Red first:** a Java file wrapped against the style fails `validate`, naming the file, before `spotless:apply`;
  after it, `validate` passes.
- The code reading's clean-up ([MX16](README.md#mx16)) comes after this, so its diff carries no formatting.

## Acceptance

- `validate` fails on a misformatted file and passes after `spotless:apply`; seen once.
- The reformatting commit changes only whitespace, line breaks (Javadoc rewrapped too) and the order of imports,
  as `google-java-format` does with its defaults: every file it touches, with its import lines sorted and all
  whitespace removed, is the same before and after.
- `.git-blame-ignore-revs` names that commit; `check-shared` passes on `.editorconfig`.
- The whole workflow green on the VM on the commit handed over.
