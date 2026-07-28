# Contributing to Mosaicast – mosaicast-plugin-sdk

Thanks for contributing!

## License
This repo is under **Apache License 2.0** (`LICENSE`). By contributing you agree your contribution is published under this license. **No CLA** — you keep your copyright.

## DCO instead of a CLA
We use the [Developer Certificate of Origin](https://developercertificate.org). Every commit needs a `Signed-off-by` line:
```bash
git commit -s -m "your message"
```
After the fact: `git commit --amend -s --no-edit` (last one) or `git rebase --signoff origin/main` (several) + `git push --force-with-lease`.
A **GitHub Action checks this on every PR**; missing sign-offs block the merge.

## SPDX header
Top of every source file:
```
// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors
```
`The Mosaicast Authors` is fixed project-wide (not your personal git name). To insert it: `pipx install reuse` -> `reuse annotate --license Apache-2.0 --copyright "The Mosaicast Authors" <file>`.

## Third-party licences in CI

This repo is the licence boundary of the project — core and every plugin compile against it — so what
the build pipeline pulls in is a deliberate choice, not just a version number.

One live example: from **`gradle/actions@v6`** the caching functionality is `gradle-actions-caching`, a
proprietary component under [Gradle's Terms of Use](https://gradle.com/legal/terms-of-use/) rather than
the action's MIT licence, and upgrading is stated to constitute accepting those terms. Gradle documents
that it "is not loaded or used when caching is disabled", so both workflows pin
`cache-disabled: true`. The build takes seconds; the cache is a convenience.

**Please don't enable caching (or bump to a caching-enabled configuration) as a drive-by change.** If the
build ever grows enough to need it, `cache-provider: basic` is the MIT-licensed alternative — but make it
an explicit decision with the licence noted in the PR. The same applies to any new action or plugin whose
terms differ from its repository's licence: a green CI run says nothing about what you agreed to.

## Translations
UI strings live in `locales/*.json` (English is the source language). Adding a language is a great first contribution: copy `locales/en.json`, translate the values, open a PR. Feed/author content is data, not UI — it is not translated.

## Before the PR
Tests green; for API changes update Javadoc/TSDoc + README.
