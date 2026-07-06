#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 The Mosaicast Authors
#
# Bump the single SemVer anchor across all four sources at once, so they never drift
# (the CI `version-parity` and `release` jobs assert they match).
#
# Usage:  scripts/set-version.sh 0.2.0
#
# After running: review the diff, commit, open a PR. Once merged, publish a GitHub Release
# tagged `v<version>` to trigger publishing (.github/workflows/release.yml).
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <version>   e.g. $0 0.2.0" >&2
  exit 2
fi
version="$1"
if ! printf '%s' "$version" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+([-+.][0-9A-Za-z.-]+)?$'; then
  echo "error: '$version' is not a SemVer string" >&2
  exit 2
fi

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

# 1. Gradle (root build) — the sole `version = "..."` assignment (indented inside subprojects {})
sed -i -E "s/(version = \")[^\"]+(\")/\1${version}\2/" build.gradle.kts

# 2. npm package + lockfile (also keeps package-lock.json in sync)
npm version --no-git-tag-version --allow-same-version "$version" >/dev/null

# 3. Java constant
sed -i -E "s/(VERSION = \")[^\"]+(\")/\1${version}\2/" \
  plugin-api/src/main/java/dev/mosaicast/plugin/api/PlatformApi.java

# 4. TypeScript constant
sed -i -E "s/(PLATFORM_API_VERSION = ')[^']+(')/\1${version}\2/" src/index.ts

echo "Set version to ${version} in build.gradle.kts, package.json, PlatformApi.java, src/index.ts."
echo "Next: git add -A && git commit -s -m \"chore: release v${version}\" && open a PR; then publish Release v${version}."
